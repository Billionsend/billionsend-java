package com.billionsend;

/** 401, or no API key configured ({@code missing_api_key}, status {@code null}): the key is missing, malformed or revoked. */
public class AuthenticationException extends BillionSendException {
  private static final long serialVersionUID = 1L;

  /**
   * @param message the API's message
   * @param name the API's error name
   * @param statusCode the HTTP status
   * @param requestId the answer's {@code x-request-id}, or {@code null}
   */
  public AuthenticationException(String message, String name, Integer statusCode, String requestId) {
    super(message, name, statusCode, requestId);
  }
}
