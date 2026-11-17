package com.billionsend;

/** 5xx: the API failed to answer the request. */
public class ApiException extends BillionSendException {
  private static final long serialVersionUID = 1L;

  /**
   * @param message the API's message
   * @param name the API's error name
   * @param statusCode the HTTP status
   * @param requestId the answer's {@code x-request-id}, or {@code null}
   */
  public ApiException(String message, String name, Integer statusCode, String requestId) {
    super(message, name, statusCode, requestId);
  }
}
