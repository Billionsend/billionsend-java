package com.billionsend.internal;

import com.billionsend.BillionSendException;

/**
 * A reconnect of an event stream that did not open: the error, and the answer's {@code
 * retry-after} as sent (or {@code null}), which the stream waits on before its next attempt.
 */
public final class StreamOpenFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient BillionSendException error;
  private final String retryAfter;

  /**
   * Wraps a failed open.
   *
   * @param error the error
   * @param retryAfter the answer's {@code retry-after}, or {@code null}
   */
  public StreamOpenFailure(BillionSendException error, String retryAfter) {
    super(error.getMessage(), error, false, false);
    this.error = error;
    this.retryAfter = retryAfter;
  }

  /** The error the open failed with. */
  public BillionSendException error() {
    return error;
  }

  /** The answer's {@code retry-after}, or {@code null}. */
  public String retryAfter() {
    return retryAfter;
  }
}
