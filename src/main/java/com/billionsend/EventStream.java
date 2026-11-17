package com.billionsend;

import com.billionsend.internal.Retry;
import com.billionsend.internal.ServerSentEvent;
import com.billionsend.internal.SseParser;
import com.billionsend.internal.StreamConnection;
import com.billionsend.internal.StreamOpenFailure;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * A live inbox event stream. Iterate it once, inside try-with-resources: closing it closes the
 * connection, and a loop blocked on the next event ends without an error.
 *
 * <pre>{@code
 * try (EventStream stream = client.inboxes().events().streamInbox(inboxId)) {
 *   for (InboxStreamItem item : stream) {
 *     ...
 *   }
 * }
 * }</pre>
 *
 * <p>When the connection drops (the server also closes every stream about every ten minutes) it
 * reconnects with {@code Last-Event-ID}, and the events in between are replayed first, unless
 * {@link EventStreamOptions.Builder#reconnect(boolean) reconnect} is off. A connection that
 * opened is no failure however it ends, so a quiet inbox's stream goes on for ever. A reconnect
 * the API refuses with a {@code retry-after} (a 429 or a 5xx) waits that long, and one longer
 * than a minute is thrown at once. After {@link EventStreamOptions#maxReconnects()} reconnects in
 * a row that fail to open, the iteration throws the last refusal ({@link RateLimitException},
 * {@link ApiException}, ...), or an {@link ApiConnectionException} when the API could not be
 * reached at all; an error a retry cannot fix (a revoked key, say) is thrown as its own
 * {@link BillionSendException} class. One inbox's stream ends after that inbox's {@code
 * inbox.deleted} or {@code inbox.expired}.
 */
public final class EventStream implements Iterable<InboxStreamItem>, AutoCloseable {
  /** The events after which one inbox's stream is over (the server closes it, and a reconnect would answer 404). */
  public static final Set<String> INBOX_GONE_EVENTS = Set.of("inbox.deleted", "inbox.expired");

  /** Opens one connection, resuming after {@code lastEventId} when it is set. */
  @FunctionalInterface
  public interface Connector {
    /**
     * Connects.
     *
     * @param lastEventId the position to resume after, or {@code null}
     * @return the open connection
     * @throws StreamOpenFailure when the stream cannot open, with the answer's retry-after
     */
    StreamConnection connect(String lastEventId);
  }

  /** Waits before reconnecting. */
  @FunctionalInterface
  public interface Sleeper {
    /**
     * Waits.
     *
     * @param millis how long
     * @throws InterruptedException when the thread is interrupted
     */
    void sleep(long millis) throws InterruptedException;
  }

  private final Connector connector;
  private final EventStreamOptions options;
  private final Set<String> endsOn;
  private final Sleeper sleeper;
  private volatile StreamConnection connection;
  private volatile boolean closed;
  private volatile String lastEventId;
  private volatile long delayMillis;
  /** A failed reconnect's retry-after: the wait before the next attempt, once. */
  private volatile Long retryAfterMillis;
  private boolean started;

  /**
   * Made by the resource classes; use {@code client.inboxes().events().stream(...)}.
   *
   * @param connection the first, open connection
   * @param connector opens the following connections
   * @param options the stream options
   * @param endsOn event names after which the stream is over
   * @param sleeper waits before reconnecting
   */
  public EventStream(
      StreamConnection connection,
      Connector connector,
      EventStreamOptions options,
      Set<String> endsOn,
      Sleeper sleeper) {
    this.connection = connection;
    this.connector = connector;
    this.options = options;
    this.endsOn = Set.copyOf(endsOn);
    this.sleeper = sleeper;
    this.lastEventId = options.lastEventId();
    this.delayMillis = options.reconnectDelay().toMillis();
  }

  /** The id of the last event received: pass it as {@code lastEventId} to resume later. */
  public String lastEventId() {
    return lastEventId;
  }

  /** Closes the connection; a running loop ends without an error. Closing twice does nothing. */
  @Override
  public void close() {
    closed = true;
    StreamConnection current = connection;
    connection = null;
    if (current != null) {
      current.close();
    }
  }

  /**
   * The events, read as the loop asks for them.
   *
   * @throws IllegalStateException on a second call: a stream can be iterated only once
   */
  @Override
  public Iterator<InboxStreamItem> iterator() {
    synchronized (this) {
      if (started) {
        throw new IllegalStateException("An EventStream can be iterated only once");
      }
      started = true;
    }
    return new Events();
  }

  private final class Events implements Iterator<InboxStreamItem> {
    private final Deque<ServerSentEvent> pending = new ArrayDeque<>();
    private final byte[] chunk = new byte[8192];
    private SseParser parser;
    private StreamConnection reading;
    private InboxStreamItem next;
    private boolean done;
    private int failures;
    private boolean gapSeen;
    private String gapAt;

    @Override
    public boolean hasNext() {
      if (next != null) {
        return true;
      }
      if (done) {
        return false;
      }
      try {
        next = advance();
      } catch (RuntimeException e) {
        finish();
        throw e;
      }
      if (next == null) {
        finish();
      }
      return next != null;
    }

    @Override
    public InboxStreamItem next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      InboxStreamItem item = next;
      next = null;
      if (endsOn.contains(item.event())) {
        finish();
      }
      return item;
    }

    private void finish() {
      done = true;
      close();
    }

    /** The next item, or {@code null} when the stream is over. */
    private InboxStreamItem advance() {
      boolean reconnect = options.reconnect();
      while (!closed) {
        StreamConnection current = connection;
        if (current != null) {
          if (reading != current) {
            reading = current;
            pending.clear();
            parser = new SseParser(ms -> delayMillis = ms, lastEventId);
          }
          ServerSentEvent event;
          while ((event = nextEvent(current)) != null) {
            failures = 0;
            if (StreamGapEvent.EVENT.equals(event.event())) {
              if (gapSeen && Objects.equals(gapAt, lastEventId)) {
                continue;
              }
              gapSeen = true;
              gapAt = lastEventId;
              return new StreamGapEvent(event.data());
            }
            lastEventId = event.id();
            return new InboxStreamEvent(
                event.id() == null ? "" : event.id(), event.event(), event.data());
          }
          current.close();
          if (connection == current) {
            connection = null;
          }
        }
        if (closed || !reconnect) {
          return null;
        }
        // A connection that opened and then ended is not a failure: the server closes
        // a quiet stream on schedule. Only reconnects that fail to open count, in a row.
        Long wait = retryAfterMillis;
        retryAfterMillis = null;
        try {
          sleeper.sleep(wait != null ? wait : delayMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        }
        if (closed) {
          return null;
        }
        StreamConnection opened;
        try {
          opened = connector.connect(lastEventId);
        } catch (StreamOpenFailure failure) {
          BillionSendException e = failure.error();
          if (!Retry.retryable(e, false)) {
            throw e;
          }
          // A 429 or 5xx with a retry-after sets the next wait; one past the limit is
          // thrown at once, as for any request.
          Double seconds = Retry.parseRetryAfter(failure.retryAfter(), System.currentTimeMillis());
          if (seconds != null) {
            double ms = seconds * 1000;
            if (ms > Retry.MAX_RETRY_AFTER_MS) {
              throw e;
            }
            retryAfterMillis = (long) Math.ceil(ms);
          }
          failures++;
          if (failures > options.maxReconnects()) {
            // The API's own answer says why; without one the stream could not connect.
            if (e instanceof ApiConnectionException) {
              throw new ApiConnectionException(
                  "The event stream could not reconnect; gave up after "
                      + options.maxReconnects()
                      + " reconnects");
            }
            throw e;
          }
          continue;
        }
        failures = 0;
        if (closed) {
          opened.close();
          return null;
        }
        connection = opened;
      }
      return null;
    }

    /** The next event of this connection, or {@code null} when it ended or dropped. */
    private ServerSentEvent nextEvent(StreamConnection current) {
      while (pending.isEmpty()) {
        if (parser == null) {
          return null;
        }
        int read;
        try {
          InputStream body = current.body();
          read = body.read(chunk);
        } catch (IOException e) {
          // The connection dropped; the caller reconnects.
          parser = null;
          return null;
        }
        if (read < 0) {
          pending.addAll(parser.finish());
          parser = null;
        } else if (read > 0) {
          pending.addAll(parser.feed(chunk, 0, read));
        }
      }
      return pending.poll();
    }
  }
}
