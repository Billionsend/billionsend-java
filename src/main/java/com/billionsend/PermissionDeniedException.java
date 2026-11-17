package com.billionsend;

/** 403: the key, its scope, the role or the plan does not allow this. */
public class PermissionDeniedException extends BillionSendException {
  private static final long serialVersionUID = 1L;

  /**
   * @param message the API's message
   * @param name the API's error name
   * @param statusCode the HTTP status
   * @param requestId the answer's {@code x-request-id}, or {@code null}
   */
  public PermissionDeniedException(String message, String name, Integer statusCode, String requestId) {
    super(message, name, statusCode, requestId);
  }
}
