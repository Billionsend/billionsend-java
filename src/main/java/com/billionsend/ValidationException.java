package com.billionsend;

/** 400 or 422: the request was refused as invalid. */
public class ValidationException extends BillionSendException {
  private static final long serialVersionUID = 1L;

  /**
   * @param message the API's message
   * @param name the API's error name
   * @param statusCode the HTTP status
   * @param requestId the answer's {@code x-request-id}, or {@code null}
   */
  public ValidationException(String message, String name, Integer statusCode, String requestId) {
    super(message, name, statusCode, requestId);
  }
}
