package com.billionsend;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Options of an inbox event stream ({@code inboxes().events().stream(...)} and {@code
 * streamInbox(...)}). Build one with {@link #builder()}; every field is optional.
 */
public final class EventStreamOptions {
  private static final EventStreamOptions NONE = builder().build();

  private final Map<String, String> headers;
  private final String lastEventId;
  private final boolean reconnect;
  private final int maxReconnects;
  private final Duration reconnectDelay;
  private final Duration timeout;
  private final Integer maxRetries;

  private EventStreamOptions(Builder builder) {
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
    this.lastEventId = builder.lastEventId;
    this.reconnect = builder.reconnect;
    this.maxReconnects = builder.maxReconnects;
    this.reconnectDelay = builder.reconnectDelay;
    this.timeout = builder.timeout;
    this.maxRetries = builder.maxRetries;
  }

  /** Options with nothing set. */
  public static EventStreamOptions none() {
    return NONE;
  }

  /** A new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Extra headers for the stream request. */
  public Map<String, String> headers() {
    return headers;
  }

  /** The event id to resume after (the {@code Last-Event-ID} header), or {@code null}. */
  public String lastEventId() {
    return lastEventId;
  }

  /** Whether to reconnect when the connection drops (default {@code true}). */
  public boolean reconnect() {
    return reconnect;
  }

  /** Reconnects in a row that fail to open before giving up (default 5); one that opens resets the count. */
  public int maxReconnects() {
    return maxReconnects;
  }

  /** The wait before reconnecting (default one second; a server {@code retry} field overrides it). */
  public Duration reconnectDelay() {
    return reconnectDelay;
  }

  /** The timeout of each connection attempt until the stream opens, or {@code null} for the client's. */
  public Duration timeout() {
    return timeout;
  }

  /** Retries of the first connection, or {@code null} for the client's. */
  public Integer maxRetries() {
    return maxRetries;
  }

  /** Builds {@link EventStreamOptions}. */
  public static final class Builder {
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String lastEventId;
    private boolean reconnect = true;
    private int maxReconnects = 5;
    private Duration reconnectDelay = Duration.ofSeconds(1);
    private Duration timeout;
    private Integer maxRetries;

    private Builder() {}

    /**
     * Adds a header to the stream request.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /**
     * Resumes after this event id (sent as the {@code Last-Event-ID} header): keep {@link
     * EventStream#lastEventId()} to resume where a previous run stopped.
     *
     * @param lastEventId the id, or {@code null} to start from now
     * @return this builder
     */
    public Builder lastEventId(String lastEventId) {
      this.lastEventId = lastEventId;
      return this;
    }

    /**
     * Whether to reconnect with {@code Last-Event-ID} when the connection drops (default {@code
     * true}).
     *
     * @param reconnect {@code false} to end the iteration when the connection ends
     * @return this builder
     */
    public Builder reconnect(boolean reconnect) {
      this.reconnect = reconnect;
      return this;
    }

    /**
     * Reconnect attempts in a row before giving up with an {@link ApiConnectionException}
     * (default 5).
     *
     * @param maxReconnects zero or more
     * @return this builder
     */
    public Builder maxReconnects(int maxReconnects) {
      if (maxReconnects < 0) {
        throw new IllegalArgumentException("maxReconnects must be zero or more");
      }
      this.maxReconnects = maxReconnects;
      return this;
    }

    /**
     * The wait before reconnecting (default one second); a {@code retry} field from the server
     * overrides it.
     *
     * @param reconnectDelay zero or more
     * @return this builder
     */
    public Builder reconnectDelay(Duration reconnectDelay) {
      if (reconnectDelay == null || reconnectDelay.isNegative()) {
        throw new IllegalArgumentException("reconnectDelay must be zero or more");
      }
      this.reconnectDelay = reconnectDelay;
      return this;
    }

    /**
     * The timeout of each connection attempt until the stream opens (default: the client's). An
     * open stream is not timed.
     *
     * @param timeout a positive duration
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      if (timeout == null || timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("timeout must be a positive duration");
      }
      this.timeout = timeout;
      return this;
    }

    /**
     * Retries of the first connection on 429, 5xx and network failures (default: the client's).
     *
     * @param maxRetries zero or more
     * @return this builder
     */
    public Builder maxRetries(int maxRetries) {
      if (maxRetries < 0) {
        throw new IllegalArgumentException("maxRetries must be zero or more");
      }
      this.maxRetries = maxRetries;
      return this;
    }

    /** The options. */
    public EventStreamOptions build() {
      return new EventStreamOptions(this);
    }
  }
}
