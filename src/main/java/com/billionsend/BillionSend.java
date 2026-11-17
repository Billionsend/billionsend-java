package com.billionsend;

import com.billionsend.internal.Transport;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The BillionSend API client. Thread-safe: make one and share it.
 *
 * <pre>{@code
 * BillionSend client = new BillionSend(); // reads BILLIONSEND_API_KEY
 * JsonNode sent = client.emails().send(Map.of(
 *     "from", "Acme <hello@acme.dev>",
 *     "to", List.of("ana@example.com"),
 *     "subject", "Welcome to Acme",
 *     "html", "<p>Glad you are here.</p>"));
 * }</pre>
 *
 * <p>Every operation is a method of a resource, named after the operation's {@code operationId}
 * ({@code emails.receiving.attachments.list} is {@code
 * client.emails().receiving().attachments().list(...)}). Methods return the answer as a Jackson
 * {@link com.fasterxml.jackson.databind.JsonNode} and throw a {@link BillionSendException} subclass
 * on failure.
 */
public final class BillionSend extends BillionSendApi {
  /** This SDK's version. */
  public static final String VERSION = "0.1.1";

  /** The API base URL used when none is configured. */
  public static final String DEFAULT_BASE_URL = "https://api.billionsend.com";

  /** The per-attempt timeout used when none is configured. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  /** The retries used when none is configured. */
  public static final int DEFAULT_MAX_RETRIES = 2;

  private final String baseUrl;

  /**
   * A client configured from the environment: the key from {@code BILLIONSEND_API_KEY}, the base
   * URL from {@code BILLIONSEND_BASE_URL} (default {@code https://api.billionsend.com}). Without a
   * key only {@code agent().signup(...)} works.
   */
  public BillionSend() {
    this(builder());
  }

  /**
   * A client with this API key ({@code bs_...}); the base URL as {@link #BillionSend()} reads it.
   *
   * @param apiKey the API key
   */
  public BillionSend(String apiKey) {
    this(builder().apiKey(Objects.requireNonNull(apiKey, "apiKey")));
  }

  private BillionSend(Builder builder) {
    this(builder, resolve(builder));
  }

  private BillionSend(Builder builder, Transport transport) {
    super(transport);
    this.baseUrl = builder.resolvedBaseUrl;
  }

  private static Transport resolve(Builder b) {
    String apiKey = b.apiKey != null ? b.apiKey : nonEmpty(b.env.apply("BILLIONSEND_API_KEY"));
    String base = b.baseUrl != null ? b.baseUrl : nonEmpty(b.env.apply("BILLIONSEND_BASE_URL"));
    if (base == null) {
      base = DEFAULT_BASE_URL;
    }
    base = base.replaceAll("/+$", "");
    URI uri;
    try {
      uri = URI.create(base);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("baseUrl is not a valid URL: " + base, e);
    }
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL: " + base);
    }
    b.resolvedBaseUrl = base;
    HttpClient http =
        b.httpClient != null
            ? b.httpClient
            : HttpClient.newBuilder()
                .connectTimeout(b.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    return new Transport(apiKey, base, b.timeout, b.maxRetries, b.headers, http, b.sleeper);
  }

  private static String nonEmpty(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  /** A builder for a client with explicit settings. */
  public static Builder builder() {
    return new Builder();
  }

  /** The API base URL this client calls. */
  public String baseUrl() {
    return baseUrl;
  }

  /** Builds a {@link BillionSend} client. */
  public static final class Builder {
    private String apiKey;
    private String baseUrl;
    private String resolvedBaseUrl;
    private Duration timeout = DEFAULT_TIMEOUT;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private HttpClient httpClient;
    private Function<String, String> env = System::getenv;
    private Transport.Sleeper sleeper = Thread::sleep;

    private Builder() {}

    /**
     * The API key ({@code bs_...}). Defaults to the {@code BILLIONSEND_API_KEY} environment
     * variable.
     *
     * @param apiKey the key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * The API base URL. Defaults to {@code BILLIONSEND_BASE_URL}, then {@code
     * https://api.billionsend.com}.
     *
     * @param baseUrl an absolute URL
     * @return this builder
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * The timeout of each attempt (default 60 seconds). Event streams are timed only until they
     * open.
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
     * Retries for 429, 5xx, timeouts and network failures (default 2). Only requests safe to repeat
     * are retried: {@code GET}, {@code PUT} and {@code DELETE}, and the sending operations, which
     * always carry an {@code Idempotency-Key}.
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
     * A header sent with every request.
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
     * The {@link HttpClient} to send requests with (default: a new one with a connect timeout of
     * {@link #timeout(Duration)} that does not follow redirects).
     *
     * @param httpClient the client
     * @return this builder
     */
    public Builder httpClient(HttpClient httpClient) {
      this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
      return this;
    }

    /** Reads environment variables from {@code env} instead of the process environment (tests). */
    Builder env(Function<String, String> env) {
      this.env = env;
      return this;
    }

    /** Waits between retries with {@code sleeper} (tests). */
    Builder sleeper(Transport.Sleeper sleeper) {
      this.sleeper = sleeper;
      return this;
    }

    /** The client. */
    public BillionSend build() {
      return new BillionSend(this);
    }
  }
}
