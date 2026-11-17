package com.billionsend.internal;

import com.billionsend.ApiConnectionException;
import com.billionsend.BillionSendException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/** When and how long to wait before retrying a request. */
public final class Retry {
  /** A {@code retry-after} longer than this is not waited for: the error is thrown instead. */
  public static final long MAX_RETRY_AFTER_MS = 60_000;

  private static final Pattern NUMBER =
      Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
  private static final long BASE_DELAY_MS = 500;
  private static final long MAX_BACKOFF_MS = 8_000;

  /** Methods safe to repeat without an {@code Idempotency-Key}. */
  public static final Set<String> IDEMPOTENT_METHODS =
      Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

  private Retry() {}

  /**
   * How long to wait before retry number {@code attempt + 1}: the server's {@code retry-after}
   * (seconds or an HTTP date) when it sent one, otherwise an exponential backoff with jitter.
   * {@code null} means "do not retry", which is the answer for a {@code retry-after} longer than
   * {@link #MAX_RETRY_AFTER_MS}.
   *
   * @param attempt the attempt that failed, from 0
   * @param retryAfter the {@code retry-after} header, or {@code null}
   * @param nowMillis the current time, to read an HTTP date against
   * @param random a source of numbers in {@code [0, 1)} for the jitter
   * @return milliseconds to wait, or {@code null}
   */
  public static Long delayMillis(
      int attempt, String retryAfter, long nowMillis, DoubleSupplier random) {
    Double seconds = parseRetryAfter(retryAfter, nowMillis);
    if (seconds != null) {
      double ms = seconds * 1000;
      return ms > MAX_RETRY_AFTER_MS ? null : (long) Math.ceil(ms);
    }
    long backoff = Math.min(BASE_DELAY_MS << Math.min(attempt, 20), MAX_BACKOFF_MS);
    return Math.round(backoff * (1 - random.getAsDouble() * 0.25));
  }

  /**
   * {@link #delayMillis(int, String, long, DoubleSupplier)} with the clock and a random jitter.
   *
   * @param attempt the attempt that failed, from 0
   * @param retryAfter the {@code retry-after} header, or {@code null}
   * @return milliseconds to wait, or {@code null}
   */
  public static Long delayMillis(int attempt, String retryAfter) {
    return delayMillis(
        attempt, retryAfter, System.currentTimeMillis(), ThreadLocalRandom.current()::nextDouble);
  }

  /**
   * Seconds from a {@code retry-after} value (seconds or an HTTP date), never negative; {@code
   * null} when it is absent or unreadable.
   *
   * @param value the header value, or {@code null}
   * @param nowMillis the current time
   * @return seconds, or {@code null}
   */
  public static Double parseRetryAfter(String value, long nowMillis) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String text = value.trim();
    if (NUMBER.matcher(text).matches()) {
      double seconds = Double.parseDouble(text);
      return Double.isFinite(seconds) ? Math.max(0, seconds) : null;
    }
    try {
      long at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
      return Math.max(0, (at - nowMillis) / 1000.0);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * Whether a failed attempt may be retried: a 429, a 5xx, a timeout or a network failure; and,
   * for a request that carries an {@code Idempotency-Key}, the {@code 409
   * concurrent_idempotent_requests} the API answers while the first attempt with that key is still
   * being processed (other 409s are final).
   *
   * @param error the failure
   * @param keyed whether the request carries an {@code Idempotency-Key}
   * @return whether to retry
   */
  public static boolean retryable(BillionSendException error, boolean keyed) {
    if (error instanceof ApiConnectionException) {
      return true;
    }
    Integer status = error.getStatusCode();
    if (status == null) {
      return false;
    }
    if (status == 429 || status >= 500) {
      return true;
    }
    return keyed && status == 409 && "concurrent_idempotent_requests".equals(error.getName());
  }

  /** A new idempotency key: a random UUID (version 4). */
  public static String newIdempotencyKey() {
    return UUID.randomUUID().toString();
  }
}
