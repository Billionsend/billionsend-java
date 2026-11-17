package com.billionsend;

/**
 * The request never got an answer: DNS, connection, TLS or protocol failure. {@link
 * #getStatusCode()} is {@code null}.
 */
public class ApiConnectionException extends BillionSendException {
  private static final long serialVersionUID = 1L;

  /**
   * @param message what failed
   * @param cause the underlying failure, or {@code null}
   */
  public ApiConnectionException(String message, Throwable cause) {
    super(message, null, null, null, cause);
  }

  /** @param message what failed */
  public ApiConnectionException(String message) {
    this(message, null);
  }
}
