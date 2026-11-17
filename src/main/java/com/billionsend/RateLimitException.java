package com.billionsend;

/** 429: a rate limit, a sending quota or a stream cap. */
public class RateLimitException extends BillionSendException {
  private static final long serialVersionUID = 1L;

  private final Double retryAfter;

  /**
   * @param message the API's message
   * @param name the API's error name
   * @param statusCode the HTTP status
   * @param requestId the answer's {@code x-request-id}, or {@code null}
   * @param retryAfter the server's {@code retry-after} hint in seconds, or {@code null}
   */
  public RateLimitException(
      String message, String name, Integer statusCode, String requestId, Double retryAfter) {
    super(message, name, statusCode, requestId);
    this.retryAfter = retryAfter;
  }

  /** The server's {@code retry-after} hint in seconds, or {@code null} without one. */
  public Double getRetryAfter() {
    return retryAfter;
  }
}
