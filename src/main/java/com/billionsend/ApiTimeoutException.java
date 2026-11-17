package com.billionsend;

/** The request timed out before the answer arrived. */
public class ApiTimeoutException extends ApiConnectionException {
  private static final long serialVersionUID = 1L;

  /** @param message what timed out */
  public ApiTimeoutException(String message) {
    super(message);
  }
}
