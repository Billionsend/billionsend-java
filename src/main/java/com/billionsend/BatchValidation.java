package com.billionsend;

/**
 * The {@code x-batch-validation} header of {@code emails.batch} and {@code contacts.batch.create}:
 * {@link #STRICT} (the default) fails the whole batch on one invalid item; {@link #PERMISSIVE}
 * writes the valid ones.
 */
public enum BatchValidation {
  /** Fail the whole batch on one invalid item. */
  STRICT("strict"),
  /** Write the valid items and report the invalid ones. */
  PERMISSIVE("permissive");

  private final String value;

  BatchValidation(String value) {
    this.value = value;
  }

  /** The header value. */
  public String value() {
    return value;
  }
}
