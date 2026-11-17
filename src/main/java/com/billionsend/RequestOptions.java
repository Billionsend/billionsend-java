package com.billionsend;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-call options: extra headers, a timeout, a retry count, the idempotency key of a send and the
 * batch validation mode. Build one with {@link #builder()}; every field is optional.
 *
 * <pre>{@code
 * RequestOptions options = RequestOptions.builder()
 *     .idempotencyKey("welcome/" + user.id())
 *     .maxRetries(4)
 *     .build();
 * }</pre>
 */
public final class RequestOptions {
  private static final RequestOptions NONE = builder().build();

  private final String idempotencyKey;
  private final Map<String, String> headers;
  private final Duration timeout;
  private final Integer maxRetries;
  private final BatchValidation batchValidation;

  private RequestOptions(Builder builder) {
    this.idempotencyKey = builder.idempotencyKey;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
    this.timeout = builder.timeout;
    this.maxRetries = builder.maxRetries;
    this.batchValidation = builder.batchValidation;
  }

  /** Options with nothing set. */
  public static RequestOptions none() {
    return NONE;
  }

  /** A new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** The {@code Idempotency-Key} to send, or {@code null}. */
  public String idempotencyKey() {
    return idempotencyKey;
  }

  /** Extra headers for this call. */
  public Map<String, String> headers() {
    return headers;
  }

  /** The per-attempt timeout for this call, or {@code null} for the client's. */
  public Duration timeout() {
    return timeout;
  }

  /** The retries for this call, or {@code null} for the client's. */
  public Integer maxRetries() {
    return maxRetries;
  }

  /** The {@code x-batch-validation} mode, or {@code null}. */
  public BatchValidation batchValidation() {
    return batchValidation;
  }

  /** Builds {@link RequestOptions}. */
  public static final class Builder {
    private String idempotencyKey;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Duration timeout;
    private Integer maxRetries;
    private BatchValidation batchValidation;

    private Builder() {}

    /**
     * Sent as the {@code Idempotency-Key} header (1 to 256 characters): a repeat with the same key
     * and payload answers the first result instead of sending again. The sending operations make a
     * new key for each call without one (a random UUID), and that call's retries reuse it; pass
     * your own to make a send safe across calls and processes too, such as {@code
     * "welcome/" + userId}. A key on any other request makes it safe to retry as well.
     *
     * @param idempotencyKey the key
     * @return this builder
     */
    public Builder idempotencyKey(String idempotencyKey) {
      this.idempotencyKey = idempotencyKey;
      return this;
    }

    /**
     * Adds a header to this call.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /**
     * Adds headers to this call.
     *
     * @param headers names and values
     * @return this builder
     */
    public Builder headers(Map<String, String> headers) {
      headers.forEach(this::header);
      return this;
    }

    /**
     * Overrides the client's per-attempt timeout for this call.
     *
     * @param timeout a positive duration
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      if (timeout == null || timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("timeout must be a positive duration");
      }
      this.timeout = timeout;
      return this;
    }

    /**
     * Overrides the client's retry count for this call.
     *
     * @param maxRetries zero or more
     * @return this builder
     */
    public Builder maxRetries(int maxRetries) {
      if (maxRetries < 0) {
        throw new IllegalArgumentException("maxRetries must be zero or more");
      }
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * The {@code x-batch-validation} header, sent by the two operations that take it ({@code
     * emails.batch} and {@code contacts.batch.create}).
     *
     * @param batchValidation the mode
     * @return this builder
     */
    public Builder batchValidation(BatchValidation batchValidation) {
      this.batchValidation = batchValidation;
      return this;
    }

    /** The options. */
    public RequestOptions build() {
      return new RequestOptions(this);
    }
  }
}
