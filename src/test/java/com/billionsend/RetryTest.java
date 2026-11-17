package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.billionsend.internal.Retry;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class RetryTest {
  static final long NOW = Instant.parse("2026-11-16T12:00:00Z").toEpochMilli();

  @Test
  void usesRetryAfterSeconds() {
    assertEquals(3000L, Retry.delayMillis(0, "3", NOW, () -> 0.5));
    assertEquals(1500L, Retry.delayMillis(0, "1.5", NOW, () -> 0.5));
    assertEquals(0L, Retry.delayMillis(0, "-4", NOW, () -> 0.5));
    assertEquals(60_000L, Retry.delayMillis(0, "60", NOW, () -> 0.5));
  }

  @Test
  void usesARetryAfterHttpDate() {
    String in10s =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(NOW + 10_000).atOffset(ZoneOffset.UTC));
    assertEquals(10_000L, Retry.delayMillis(0, in10s, NOW, () -> 0.5));
    assertEquals(0L, Retry.delayMillis(0, "Mon, 16 Nov 2026 11:00:00 GMT", NOW, () -> 0.5));
  }

  @Test
  void givesUpOnARetryAfterPastTheCap() {
    assertNull(Retry.delayMillis(0, "61", NOW, () -> 0.5));
    assertNull(Retry.delayMillis(0, "Mon, 16 Nov 2026 12:05:00 GMT", NOW, () -> 0.5));
  }

  @Test
  void backsOffExponentiallyWithJitterWhenThereIsNoUsableRetryAfter() {
    assertEquals(500L, Retry.delayMillis(0, null, NOW, () -> 0));
    assertEquals(1000L, Retry.delayMillis(1, "", NOW, () -> 0));
    assertEquals(2000L, Retry.delayMillis(2, "soon", NOW, () -> 0));
    assertEquals(8000L, Retry.delayMillis(5, null, NOW, () -> 0));
    assertEquals(8000L, Retry.delayMillis(40, null, NOW, () -> 0));
    assertEquals(375L, Retry.delayMillis(0, null, NOW, () -> 1));
    assertEquals(6000L, Retry.delayMillis(9, null, NOW, () -> 1));
  }

  @Test
  void retries429_5xxTimeoutsAndNetworkFailures409OnlyWhileAKeyedFirstAttemptRuns() {
    assertTrue(Retry.retryable(new RateLimitException("x", "rate_limit_exceeded", 429, null, null), false));
    assertTrue(Retry.retryable(new ApiException("x", "internal_error", 500, null), false));
    assertTrue(Retry.retryable(new ApiConnectionException("x"), false));
    assertTrue(Retry.retryable(new ApiTimeoutException("x"), false));
    assertFalse(Retry.retryable(new ValidationException("x", "validation_error", 422, null), true));
    assertFalse(Retry.retryable(new BillionSendException("aborted"), true));
    BillionSendException concurrent = new ConflictException("x", "concurrent_idempotent_requests", 409, null);
    assertTrue(Retry.retryable(concurrent, true));
    assertFalse(Retry.retryable(concurrent, false));
    assertFalse(Retry.retryable(new ConflictException("x", "invalid_idempotent_request", 409, null), true));
  }

  @Test
  void makesAVersion4UuidNewEachTime() {
    String a = Retry.newIdempotencyKey();
    String b = Retry.newIdempotencyKey();
    assertTrue(a.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), a);
    assertNotEquals(a, b);
  }
}
