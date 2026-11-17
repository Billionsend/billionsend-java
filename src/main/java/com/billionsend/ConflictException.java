package com.billionsend;

/** 409: the request conflicts with the current state, or with an earlier use of its idempotency key. */
public class ConflictException extends BillionSendException {
  private static final long serialVersionUID = 1L;

  /**
   * @param message the API's message
   * @param name the API's error name
   * @param statusCode the HTTP status
   * @param requestId the answer's {@code x-request-id}, or {@code null}
   */
  public ConflictException(String message, String name, Integer statusCode, String requestId) {
    super(message, name, statusCode, requestId);
  }
}
