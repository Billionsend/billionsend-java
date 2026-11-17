package com.billionsend;

/**
 * Every error the SDK throws. Tell the kinds apart by class: {@link ValidationException},
 * {@link AuthenticationException}, {@link PermissionDeniedException}, {@link NotFoundException},
 * {@link ConflictException}, {@link RateLimitException}, {@link ApiException},
 * {@link ApiConnectionException}, {@link ApiTimeoutException} and
 * {@link WebhookVerificationException}.
 *
 * <p>{@link #getName()} is the API's error name ({@code "validation_error"}, {@code
 * "invalid_api_key"}, ...), not the class name; failures that never got an API answer use {@code
 * "application_error"}, with {@link #getStatusCode()} {@code null} when there was no HTTP response
 * at all. {@link #getRequestId()} is the answer's {@code x-request-id} (the id of the request on the
 * dashboard's Logs page), {@code null} without one.
 */
public class BillionSendException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The name of errors the client made up without an API answer. */
  public static final String APPLICATION_ERROR = "application_error";

  private final String name;
  private final Integer statusCode;
  private final String requestId;

  /**
   * @param message the human-readable message
   * @param name the API's error name; {@code "application_error"} when {@code null}
   * @param statusCode the HTTP status, {@code null} without an answer
   * @param requestId the answer's {@code x-request-id}, or {@code null}
   */
  public BillionSendException(String message, String name, Integer statusCode, String requestId) {
    this(message, name, statusCode, requestId, null);
  }

  /**
   * @param message the human-readable message
   * @param name the API's error name; {@code "application_error"} when {@code null}
   * @param statusCode the HTTP status, {@code null} without an answer
   * @param requestId the answer's {@code x-request-id}, or {@code null}
   * @param cause the underlying failure, or {@code null}
   */
  public BillionSendException(
      String message, String name, Integer statusCode, String requestId, Throwable cause) {
    super(message, cause);
    this.name = name == null ? APPLICATION_ERROR : name;
    this.statusCode = statusCode;
    this.requestId = requestId;
  }

  /** An error the client made up without an API answer ({@code application_error}). */
  public BillionSendException(String message) {
    this(message, null, null, null, null);
  }

  /**
   * The API's error name, such as {@code "validation_error"}; {@code "application_error"} when the
   * API sent none.
   */
  public String getName() {
    return name;
  }

  /** The HTTP status of the answer, or {@code null} when there was no HTTP answer. */
  public Integer getStatusCode() {
    return statusCode;
  }

  /** The answer's {@code x-request-id}, or {@code null}. */
  public String getRequestId() {
    return requestId;
  }
}
