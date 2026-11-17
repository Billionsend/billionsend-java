package com.billionsend;

/**
 * A webhook whose signature, timestamp or body did not verify ({@link WebhookVerifier}). Its
 * name is {@code webhook_verification_error}.
 */
public class WebhookVerificationException extends BillionSendException {
  private static final long serialVersionUID = 1L;

  /** @param message what did not verify */
  public WebhookVerificationException(String message) {
    super(message, "webhook_verification_error", null, null);
  }
}
