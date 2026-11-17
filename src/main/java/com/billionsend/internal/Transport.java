package com.billionsend.internal;

import com.billionsend.ApiConnectionException;
import com.billionsend.ApiException;
import com.billionsend.ApiTimeoutException;
import com.billionsend.AuthenticationException;
import com.billionsend.BillionSend;
import com.billionsend.BillionSendException;
import com.billionsend.ConflictException;
import com.billionsend.EventStream;
import com.billionsend.EventStreamOptions;
import com.billionsend.NotFoundException;
import com.billionsend.PermissionDeniedException;
import com.billionsend.RateLimitException;
import com.billionsend.RequestOptions;
import com.billionsend.ValidationException;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The HTTP layer shared by every resource: headers, retries, errors, JSON. */
public final class Transport {
  /** Waits between retries; replaceable in tests. */
  @FunctionalInterface
  public interface Sleeper {
    /**
     * Waits.
     *
     * @param millis how long
     * @throws InterruptedException when the thread is interrupted
     */
    void sleep(long millis) throws InterruptedException;
  }

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

  private final String apiKey;
  private final String baseUrl;
  private final Duration timeout;
  private final int maxRetries;
  private final Map<String, String> headers;
  private final HttpClient http;
  private final Sleeper sleeper;

  /**
   * @param apiKey the API key, or {@code null}
   * @param baseUrl the API base URL, without a trailing slash
   * @param timeout the per-attempt timeout
   * @param maxRetries retries of failures worth retrying
   * @param headers headers sent with every request
   * @param http the HTTP client
   * @param sleeper waits between retries
   */
  public Transport(
      String apiKey,
      String baseUrl,
      Duration timeout,
      int maxRetries,
      Map<String, String> headers,
      HttpClient http,
      Sleeper sleeper) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.timeout = timeout;
    this.maxRetries = maxRetries;
    this.headers = Map.copyOf(headers);
    this.http = http;
    this.sleeper = sleeper;
  }

  /**
   * Calls one operation and parses its JSON answer.
   *
   * @param call the operation and its arguments
   * @param options per-call options, or {@code null}
   * @return the answer
   * @throws BillionSendException when the call fails
   */
  public JsonNode execute(Call call, RequestOptions options) {
    return (JsonNode) run(call, options, Map.of(), false);
  }

  /**
   * Opens an inbox event stream.
   *
   * @param call the stream operation and its arguments
   * @param options stream options, or {@code null}
   * @param endsOn event names after which the stream is over
   * @return the open stream
   * @throws BillionSendException when the stream cannot open
   */
  public EventStream stream(Call call, EventStreamOptions options, Set<String> endsOn) {
    EventStreamOptions o = options == null ? EventStreamOptions.none() : options;
    StreamConnection first = openStream(call, o, o.lastEventId(), o.maxRetries());
    return new EventStream(
        first, lastEventId -> openStream(call, o, lastEventId, 0), o, endsOn, sleeper::sleep);
  }

  private StreamConnection openStream(
      Call call, EventStreamOptions o, String lastEventId, Integer retries) {
    RequestOptions.Builder request = RequestOptions.builder().headers(o.headers());
    if (o.timeout() != null) {
      request.timeout(o.timeout());
    }
    if (retries != null) {
      request.maxRetries(retries);
    }
    Map<String, String> extra = new LinkedHashMap<>();
    if (lastEventId != null) {
      extra.put("last-event-id", lastEventId);
    }
    return (StreamConnection) run(call, request.build(), extra, true);
  }

  private Object run(
      Call call, RequestOptions requestOptions, Map<String, String> extraHeaders, boolean stream) {
    RequestOptions options = requestOptions == null ? RequestOptions.none() : requestOptions;
    if (call.auth && apiKey == null) {
      throw new AuthenticationException(
          "No API key: pass one to BillionSend.builder().apiKey(...) or set BILLIONSEND_API_KEY",
          "missing_api_key",
          null,
          null);
    }
    URI uri = URI.create(baseUrl + fillPath(call) + queryString(call.query));

    Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    h.put("accept", stream ? "text/event-stream" : "application/json");
    h.put("user-agent", "billionsend-java/" + BillionSend.VERSION);
    h.putAll(headers);
    h.putAll(options.headers());
    h.putAll(call.headers);
    h.putAll(extraHeaders);
    if (call.batchValidation && options.batchValidation() != null) {
      h.put("x-batch-validation", options.batchValidation().value());
    }
    byte[] body = null;
    if (call.body != null) {
      try {
        body = Json.MAPPER.writeValueAsBytes(call.body);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("The request body cannot be written as JSON", e);
      }
      h.put("content-type", "application/json");
    }
    if (options.idempotencyKey() != null) {
      h.put("idempotency-key", options.idempotencyKey());
    } else if (call.idempotent && !h.containsKey("idempotency-key")) {
      h.put("idempotency-key", Retry.newIdempotencyKey());
    }
    if (call.auth) {
      h.put("authorization", "Bearer " + apiKey);
    } else {
      h.remove("authorization");
    }

    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .method(
                call.method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    h.forEach(builder::setHeader);
    HttpRequest request = builder.build();

    boolean keyed = h.containsKey("idempotency-key");
    boolean retryable = Retry.IDEMPOTENT_METHODS.contains(call.method) || keyed;
    int retries = options.maxRetries() != null ? options.maxRetries() : maxRetries;
    Duration wait = options.timeout() != null ? options.timeout() : timeout;

    for (int attempt = 0; ; attempt++) {
      Failure failure;
      try {
        return stream ? attemptStream(request, wait) : attempt(request, wait);
      } catch (Failure f) {
        failure = f;
      }
      BillionSendException error = failure.error;
      if (!retryable || attempt >= retries || !Retry.retryable(error, keyed)) {
        throw error;
      }
      Long delay = Retry.delayMillis(attempt, failure.retryAfter);
      if (delay == null) {
        throw error;
      }
      try {
        sleeper.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BillionSendException("Request was interrupted");
      }
    }
  }

  /** A failed attempt: the error, and the answer's {@code retry-after}. */
  private static final class Failure extends Exception {
    private static final long serialVersionUID = 1L;
    final transient BillionSendException error;
    final String retryAfter;

    Failure(BillionSendException error, String retryAfter) {
      super(null, null, false, false);
      this.error = error;
      this.retryAfter = retryAfter;
    }
  }

  private <T> HttpResponse<T> send(
      HttpRequest request, HttpResponse.BodyHandler<T> handler, Duration wait) throws Failure {
    CompletableFuture<HttpResponse<T>> future = http.sendAsync(request, handler);
    try {
      return future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new Failure(timedOut(wait), null);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new BillionSendException("Request was interrupted");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof HttpTimeoutException) {
        throw new Failure(timedOut(wait), null);
      }
      throw new Failure(new ApiConnectionException("Network error: " + describe(cause), cause), null);
    }
  }

  private static ApiTimeoutException timedOut(Duration wait) {
    return new ApiTimeoutException("Request timed out after " + wait.toMillis() + " ms");
  }

  private static String describe(Throwable cause) {
    String message = cause.getMessage();
    String text = message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    Throwable inner = cause.getCause();
    if (inner != null && inner.getMessage() != null) {
      text += " (" + inner.getMessage() + ")";
    }
    return text;
  }

  private JsonNode attempt(HttpRequest request, Duration wait) throws Failure {
    HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray(), wait);
    int status = response.statusCode();
    String requestId = response.headers().firstValue("x-request-id").orElse(null);
    String text = new String(response.body(), StandardCharsets.UTF_8);
    if (status >= 200 && status < 300) {
      try {
        JsonNode node = Json.MAPPER.readTree(text);
        if (node == null || node.isMissingNode()) {
          throw new IllegalStateException("empty");
        }
        return node;
      } catch (JacksonException | IllegalStateException e) {
        throw new BillionSendException(
            "The response body is not valid JSON", null, status, requestId, e);
      }
    }
    throw new Failure(
        errorFromResponse(status, text, response.headers().map()),
        response.headers().firstValue("retry-after").orElse(null));
  }

  private StreamConnection attemptStream(HttpRequest request, Duration wait) throws Failure {
    HttpResponse<InputStream> response =
        send(request, HttpResponse.BodyHandlers.ofInputStream(), wait);
    int status = response.statusCode();
    String requestId = response.headers().firstValue("x-request-id").orElse(null);
    if (status >= 200 && status < 300) {
      String contentType = response.headers().firstValue("content-type").orElse("");
      if (!contentType.contains("text/event-stream")) {
        new StreamConnection(response).close();
        throw new BillionSendException(
            "Expected an event stream, got "
                + (contentType.isEmpty() ? "no content type" : contentType),
            null,
            status,
            requestId);
      }
      return new StreamConnection(response);
    }
    String text;
    try (InputStream in = response.body()) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      text = "";
    }
    throw new Failure(
        errorFromResponse(status, text, response.headers().map()),
        response.headers().firstValue("retry-after").orElse(null));
  }

  /**
   * Maps a non-2xx answer to its exception class, keeping the API's own {@code ErrorResponse} when
   * it sent one.
   *
   * @param status the HTTP status
   * @param text the body
   * @param responseHeaders the answer's headers
   * @return the exception
   */
  public static BillionSendException errorFromResponse(
      int status, String text, Map<String, ? extends Collection<String>> responseHeaders) {
    Map<String, String> lower = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    responseHeaders.forEach(
        (key, values) -> {
          if (key != null && !values.isEmpty()) {
            lower.put(key, values.iterator().next());
          }
        });
    String name = BillionSendException.APPLICATION_ERROR;
    String message = null;
    try {
      JsonNode body = text.isEmpty() ? null : Json.MAPPER.readTree(text);
      if (body != null
          && body.isObject()
          && body.path("name").isTextual()
          && body.path("message").isTextual()) {
        name = body.get("name").asText();
        message = body.get("message").asText();
      }
    } catch (JacksonException ignored) {
      // Not the API's error shape.
    }
    if (message == null) {
      String detail = text.strip();
      if (detail.length() > 200) {
        detail = detail.substring(0, 200);
      }
      message =
          detail.isEmpty()
              ? "Unexpected " + status + " response"
              : "Unexpected " + status + " response: " + detail;
    }
    String requestId = lower.get("x-request-id");
    switch (status) {
      case 400:
      case 422:
        return new ValidationException(message, name, status, requestId);
      case 401:
        return new AuthenticationException(message, name, status, requestId);
      case 403:
        return new PermissionDeniedException(message, name, status, requestId);
      case 404:
        return new NotFoundException(message, name, status, requestId);
      case 409:
        return new ConflictException(message, name, status, requestId);
      case 429:
        return new RateLimitException(
            message,
            name,
            status,
            requestId,
            Retry.parseRetryAfter(lower.get("retry-after"), System.currentTimeMillis()));
      default:
        return status >= 500
            ? new ApiException(message, name, status, requestId)
            : new BillionSendException(message, name, status, requestId);
    }
  }

  /** Fills {@code {name}} placeholders, or explains which parameter is missing or empty. */
  private static String fillPath(Call call) {
    Matcher matcher = PLACEHOLDER.matcher(call.template);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String name = matcher.group(1);
      String value = call.pathParams.get(name);
      if (value == null || value.isEmpty()) {
        throw new BillionSendException("Missing path parameter \"" + name + "\"");
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(encodePathSegment(value)));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /**
   * Percent-encodes one path segment: everything but {@code A-Z a-z 0-9 - _ . ! ~ * ' ( )}.
   *
   * @param value the raw segment
   * @return the encoded segment
   */
  public static String encodePathSegment(String value) {
    StringBuilder out = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xff);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || "-_.!~*'()".indexOf(c) >= 0) {
        out.append(c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xf, 16)));
        out.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
      }
    }
    return out.toString();
  }

  /**
   * The query string: {@code null} values dropped, lists (a {@code Collection} or an array) sent
   * comma-separated in one key, as the API declares its list parameters (form, explode false).
   *
   * @param query the parameters
   * @return {@code ""} or {@code "?..."}
   */
  public static String queryString(Map<String, ?> query) {
    StringJoiner joiner = new StringJoiner("&", "?", "");
    joiner.setEmptyValue("");
    for (Map.Entry<String, ?> entry : query.entrySet()) {
      Object value = entry.getValue();
      if (value == null) {
        continue;
      }
      String text;
      if (value instanceof Collection<?>) {
        StringJoiner items = new StringJoiner(",");
        for (Object item : (Collection<?>) value) {
          items.add(String.valueOf(item));
        }
        text = items.toString();
      } else if (value instanceof Object[]) {
        StringJoiner items = new StringJoiner(",");
        for (Object item : (Object[]) value) {
          items.add(String.valueOf(item));
        }
        text = items.toString();
      } else {
        text = String.valueOf(value);
      }
      joiner.add(
          URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
              + "="
              + URLEncoder.encode(text, StandardCharsets.UTF_8));
    }
    return joiner.toString();
  }
}
