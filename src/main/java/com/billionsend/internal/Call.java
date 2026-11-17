package com.billionsend.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One operation call, as a resource method describes it to the {@link Transport}. */
public final class Call {
  final String method;
  final String template;
  final Map<String, String> pathParams = new LinkedHashMap<>();
  final Map<String, String> headers = new LinkedHashMap<>();
  Map<String, ?> query = Collections.emptyMap();
  Object body;
  boolean idempotent;
  boolean batchValidation;
  boolean auth = true;

  private Call(String method, String template) {
    this.method = method;
    this.template = template;
  }

  /**
   * A call of the operation at {@code method} and {@code template}.
   *
   * @param method the HTTP method, upper case
   * @param template the path template, such as {@code /emails/{id}}
   * @return the call
   */
  public static Call of(String method, String template) {
    return new Call(method, template);
  }

  /**
   * Fills a {@code {name}} placeholder of the path.
   *
   * @param name the placeholder
   * @param value its value (encoded by the transport)
   * @return this call
   */
  public Call path(String name, String value) {
    pathParams.put(name, value);
    return this;
  }

  /**
   * The query parameters; {@code null} values are dropped and lists are sent comma-separated.
   *
   * @param query the parameters, or {@code null}
   * @return this call
   */
  public Call query(Map<String, ?> query) {
    this.query = query == null ? Collections.emptyMap() : query;
    return this;
  }

  /**
   * The JSON request body; {@code null} sends none.
   *
   * @param body any value the JSON mapper can write
   * @return this call
   */
  public Call body(Object body) {
    this.body = body;
    return this;
  }

  /**
   * Adds a header to this call.
   *
   * @param name the header name
   * @param value the value; {@code null} leaves the header out
   * @return this call
   */
  public Call header(String name, String value) {
    if (value != null) {
      headers.put(name, value);
    }
    return this;
  }

  /**
   * The operation honours {@code Idempotency-Key}: one is always sent, the caller's or a new one.
   *
   * @return this call
   */
  public Call idempotent() {
    this.idempotent = true;
    return this;
  }

  /**
   * The operation takes the {@code x-batch-validation} header, from {@code
   * RequestOptions.batchValidation()}.
   *
   * @return this call
   */
  public Call batchValidation() {
    this.batchValidation = true;
    return this;
  }

  /**
   * The operation takes no API key, and none is sent to it.
   *
   * @return this call
   */
  public Call noAuth() {
    this.auth = false;
    return this;
  }
}
