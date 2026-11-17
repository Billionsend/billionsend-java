package com.billionsend.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A local HTTP server that plays the API from the snapshot: it checks every request against its
 * operation (auth, query, headers, body schema), records it, and answers with a scripted response
 * or a sample of the operation's success schema. A request outside the snapshot is a violation.
 */
public final class ContractMock {
  /** One request the mock received. */
  public static final class Hit {
    public final String key;
    public final String method;
    public final String rawPath;
    public final Map<String, List<String>> query;
    public final Map<String, String> headers;
    public final String body;
    public volatile JsonNode response;

    Hit(String key, String method, String rawPath, Map<String, List<String>> query, Map<String, String> headers, String body) {
      this.key = key;
      this.method = method;
      this.rawPath = rawPath;
      this.query = query;
      this.headers = headers;
      this.body = body;
    }

    /** The first value of a query parameter, or {@code null}. */
    public String query(String name) {
      List<String> values = query.get(name);
      return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** A request header by lower-case name, or {@code null}. */
    public String header(String name) {
      return headers.get(name);
    }

    /** The body as JSON. */
    public JsonNode json() {
      try {
        return ContractSpec.MAPPER.readTree(body);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Writes a scripted event stream. */
  public interface SseWriter {
    /** Writes and flushes text; returns false once the client has gone. */
    boolean write(String text);

    /** Ends the response. */
    void end();
  }

  /** A scripted event stream. */
  @FunctionalInterface
  public interface StreamScript {
    void run(SseWriter writer) throws Exception;
  }

  /** Answers one request; whatever it records on the hit is recorded before the answer is written. */
  private interface Script {
    void answer(HttpExchange exchange, Hit hit) throws Exception;
  }

  private final Map<String, Deque<Script>> scripts = new HashMap<>();
  public final List<Hit> hits = new CopyOnWriteArrayList<>();
  public final List<String> violations = new CopyOnWriteArrayList<>();
  private volatile boolean minimal;
  private HttpServer server;
  private ExecutorService executor;

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    executor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "contract-mock");
              t.setDaemon(true);
              return t;
            });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  public void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Generated answers carry only the required properties (else every property). */
  public void minimal(boolean minimal) {
    this.minimal = minimal;
  }

  public synchronized void reset() {
    scripts.clear();
    hits.clear();
    violations.clear();
  }

  public List<Hit> hitsFor(String key) {
    List<Hit> out = new ArrayList<>();
    for (Hit hit : hits) {
      if (hit.key.equals(key)) {
        out.add(hit);
      }
    }
    return out;
  }

  private synchronized void push(String key, Script script) {
    ContractSpec.operation(key);
    scripts.computeIfAbsent(key, k -> new ArrayDeque<>()).add(script);
  }

  private synchronized Script next(String key) {
    Deque<Script> queue = scripts.get(key);
    return queue == null ? null : queue.poll();
  }

  /** Answers the next call of {@code key} with this status, JSON body and headers. */
  public void reply(String key, int status, Object body, Map<String, String> headers) {
    push(
        key,
        (exchange, hit) -> {
          JsonNode json = ContractSpec.MAPPER.valueToTree(body);
          send(exchange, hit, json, status, json.toString(), "application/json", headers);
        });
  }

  public void reply(String key, int status, Object body) {
    reply(key, status, body, Map.of());
  }

  /** The API's error body. */
  public static Map<String, Object> error(int status, String name, String message) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("statusCode", status);
    out.put("name", name);
    out.put("message", message);
    return out;
  }

  /** Answers the next call of {@code key} with a raw body and content type. */
  public void replyText(String key, int status, String body, String contentType) {
    push(
        key,
        (exchange, hit) -> {
          send(exchange, hit, null, status, body, contentType, Map.of());
        });
  }

  /** Answers the next call of {@code key} after a delay. */
  public void replyAfter(String key, long millis) {
    push(
        key,
        (exchange, hit) -> {
          Thread.sleep(millis);
          sampleAnswer(exchange, hit, ContractSpec.operation(key));
        });
  }

  /** Drops the connection of the next call of {@code key} without an answer. */
  public void drop(String key) {
    push(
        key,
        (exchange, hit) -> {
          throw new DropConnection();
        });
  }

  private static final class DropConnection extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  /**
   * Answers the next call of {@code key} with an event stream the script writes. If the script
   * returns without {@code end()}, the stream stays open until the client goes; the returned latch
   * opens then.
   */
  public CountDownLatch stream(String key, StreamScript script) {
    CountDownLatch closed = new CountDownLatch(1);
    push(
        key,
        (exchange, hit) -> {
          exchange.getResponseHeaders().set("content-type", "text/event-stream");
          exchange.getResponseHeaders().set("x-request-id", "req_mock");
          exchange.sendResponseHeaders(200, 0);
          OutputStream out = exchange.getResponseBody();
          boolean[] ended = {false};
          boolean[] gone = {false};
          SseWriter writer =
              new SseWriter() {
                @Override
                public boolean write(String text) {
                  try {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return true;
                  } catch (IOException e) {
                    gone[0] = true;
                    return false;
                  }
                }

                @Override
                public void end() {
                  ended[0] = true;
                }
              };
          try {
            script.run(writer);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!ended[0] && !gone[0] && System.nanoTime() < until) {
              Thread.sleep(20);
              writer.write(":\n");
            }
          } finally {
            try {
              out.close();
            } catch (IOException ignored) {
              // The client went first.
            }
            closed.countDown();
          }
        });
    return closed;
  }

  /**
   * Records {@code answer} on the hit, then writes the response. The order matters: once the last
   * byte is out, the client may return and the test read the hit before this thread runs again.
   */
  private static void send(
      HttpExchange exchange, Hit hit, JsonNode answer, int status, String body, String contentType, Map<String, String> headers)
      throws IOException {
    if (hit != null) {
      hit.response = answer;
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("content-type", contentType);
    exchange.getResponseHeaders().set("x-request-id", "req_mock");
    headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private void sampleAnswer(HttpExchange exchange, Hit hit, ContractSpec.Operation op) throws IOException {
    if (op.isStream()) {
      exchange.getResponseHeaders().set("content-type", "text/event-stream");
      exchange.getResponseHeaders().set("x-request-id", "req_mock");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(
            ("id: 1.1.1\nevent: inbox.email.received\ndata: {\"type\":\"inbox.email.received\","
                    + "\"created_at\":\"2026-11-16T12:00:00.000Z\",\"inbox_id\":\"i\","
                    + "\"data\":{\"inbox_id\":\"i\",\"thread_id\":\"t\",\"email_id\":\"e\"}}\n\n")
                .getBytes(StandardCharsets.UTF_8));
      }
      return;
    }
    String status = op.successStatus();
    if (status == null) {
      JsonNode error = ContractSpec.MAPPER.valueToTree(error(501, "not_implemented", "No success answer"));
      send(exchange, hit, error, 501, error.toString(), "application/json", Map.of());
      return;
    }
    JsonNode sample = ContractSpec.example(op.responseSchema(status), !minimal);
    List<String> errors = ContractSpec.validate(op.responsePointer(status), sample);
    if (!errors.isEmpty()) {
      violations.add(op.key + ": the generated answer does not validate: " + errors);
    }
    send(exchange, hit, sample, Integer.parseInt(status), sample.toString(), "application/json", Map.of());
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String method = exchange.getRequestMethod();
      String rawPath = exchange.getRequestURI().getRawPath();
      Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
      Map<String, String> headers = new TreeMap<>();
      exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      ContractSpec.Operation op = ContractSpec.match(method, rawPath);
      Hit hit = new Hit(op == null ? "UNKNOWN " + method + " " + rawPath : op.key, method, rawPath, query, Collections.unmodifiableMap(headers), body);
      hits.add(hit);
      if (op == null) {
        violations.add("no operation for " + method + " " + rawPath);
        JsonNode error = ContractSpec.MAPPER.valueToTree(error(404, "not_found", "No such endpoint"));
        send(exchange, hit, error, 404, error.toString(), "application/json", Map.of());
        return;
      }
      check(op, hit);
      Script script = next(op.key);
      if (script == null) {
        sampleAnswer(exchange, hit, op);
      } else {
        script.answer(exchange, hit);
      }
    } catch (DropConnection e) {
      // Leave without an answer: the server closes the connection.
    } catch (IOException e) {
      // The client went away (a timeout or a closed stream) before the answer.
    } catch (Exception e) {
      violations.add("mock failure: " + e);
    } finally {
      exchange.close();
    }
  }

  private static Map<String, List<String>> parseQuery(String raw) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
    return out;
  }

  private void check(ContractSpec.Operation op, Hit hit) {
    String auth = hit.header("authorization");
    if (op.needsAuth() && (auth == null || !auth.startsWith("Bearer bs_"))) {
      violations.add(op.key + ": no bearer API key");
    }
    if (!op.needsAuth() && auth != null) {
      violations.add(op.key + ": sent an API key to an operation that takes none");
    }
    String agent = hit.header("user-agent");
    if (agent == null || !agent.startsWith("billionsend-java/")) {
      violations.add(op.key + ": user agent " + agent);
    }
    String accept = hit.header("accept");
    String expected = op.isStream() ? "text/event-stream" : "application/json";
    if (!expected.equals(accept)) {
      violations.add(op.key + ": accept " + accept);
    }

    // Path parameters: present, and valid against their schema.
    Map<String, String> pathValues = pathParams(op.path, hit.rawPath);
    for (Map.Entry<Integer, JsonNode> entry : op.indexedParameters("path").entrySet()) {
      String name = entry.getValue().path("name").asText();
      String value = pathValues.get(name);
      if (value == null || value.isEmpty()) {
        violations.add(op.key + ": path parameter " + name + " missing");
        continue;
      }
      List<String> errors = ContractSpec.validate(op.pointer + "/parameters/" + entry.getKey() + "/schema", TextNode.valueOf(value));
      if (!errors.isEmpty()) {
        violations.add(op.key + ": path parameter " + name + ": " + errors);
      }
    }

    // Query parameters: declared, once each, required ones present, values valid once coerced.
    Map<String, Integer> declared = new LinkedHashMap<>();
    op.indexedParameters("query").forEach((i, p) -> declared.put(p.path("name").asText(), i));
    for (Map.Entry<String, List<String>> entry : hit.query.entrySet()) {
      Integer index = declared.get(entry.getKey());
      if (index == null) {
        violations.add(op.key + ": undeclared query parameter " + entry.getKey());
        continue;
      }
      if (entry.getValue().size() != 1) {
        violations.add(op.key + ": query parameter " + entry.getKey() + " sent more than once");
      }
      JsonNode schema = op.node.path("parameters").get(index).path("schema");
      JsonNode instance = coerce(entry.getValue().get(0), schema);
      List<String> errors = ContractSpec.validate(op.pointer + "/parameters/" + index + "/schema", instance);
      if (!errors.isEmpty()) {
        violations.add(op.key + ": query " + entry.getKey() + "=" + entry.getValue().get(0) + ": " + errors);
      }
    }
    op.indexedParameters("query").values().forEach(
        p -> {
          if (p.path("required").asBoolean(false) && !hit.query.containsKey(p.path("name").asText())) {
            violations.add(op.key + ": missing required query parameter " + p.path("name").asText());
          }
        });

    // Header parameters: required ones present, values valid; x-batch-validation only where declared.
    Set<String> declaredHeaders = new TreeSet<>();
    for (Map.Entry<Integer, JsonNode> entry : op.indexedParameters("header").entrySet()) {
      String name = entry.getValue().path("name").asText().toLowerCase();
      declaredHeaders.add(name);
      String value = hit.header(name);
      if (value == null) {
        if (entry.getValue().path("required").asBoolean(false)) {
          violations.add(op.key + ": required header " + name + " missing");
        }
        continue;
      }
      List<String> errors = ContractSpec.validate(op.pointer + "/parameters/" + entry.getKey() + "/schema", TextNode.valueOf(value));
      if (!errors.isEmpty()) {
        violations.add(op.key + ": header " + name + ": " + errors);
      }
    }
    if (hit.header("x-batch-validation") != null && !declaredHeaders.contains("x-batch-validation")) {
      violations.add(op.key + ": x-batch-validation sent to an operation that does not take it");
    }
    String key = hit.header("idempotency-key");
    if (op.idempotent() && (key == null || key.isEmpty() || key.length() > 256)) {
      violations.add(op.key + ": no Idempotency-Key");
    }

    // Body: present when required, absent when the operation takes none, valid JSON against its schema.
    if (!op.hasBody()) {
      if (!hit.body.isEmpty()) {
        violations.add(op.key + ": sent a body to an operation that takes none");
      }
      return;
    }
    if (hit.body.isEmpty()) {
      if (op.bodyRequired()) {
        violations.add(op.key + ": missing the required body");
      }
      return;
    }
    if (!"application/json".equals(hit.header("content-type"))) {
      violations.add(op.key + ": content type " + hit.header("content-type"));
    }
    JsonNode json;
    try {
      json = ContractSpec.MAPPER.readTree(hit.body);
    } catch (JsonProcessingException e) {
      violations.add(op.key + ": body is not JSON");
      return;
    }
    List<String> errors = ContractSpec.validate(op.requestPointer(), json);
    if (!errors.isEmpty()) {
      violations.add(op.key + ": body does not validate: " + errors);
    }
  }

  private static Map<String, String> pathParams(String template, String rawPath) {
    Map<String, String> out = new LinkedHashMap<>();
    String[] names = template.substring(1).split("/");
    String[] values = rawPath.substring(1).split("/", -1);
    for (int i = 0; i < names.length && i < values.length; i++) {
      if (names[i].startsWith("{")) {
        out.put(
            names[i].substring(1, names[i].length() - 1),
            URLDecoder.decode(values[i].replace("+", "%2B"), StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  /** A query value as the JSON its schema describes: lists split on commas, numbers, booleans. */
  private static JsonNode coerce(String value, JsonNode input) {
    JsonNode schema = ContractSpec.resolve(input);
    if (schema.path("type").asText().equals("array")) {
      ArrayNode out = ContractSpec.MAPPER.createArrayNode();
      for (String item : value.split(",", -1)) {
        out.add(scalar(item, ContractSpec.resolve(schema.path("items"))));
      }
      return out;
    }
    return scalar(value, schema);
  }

  private static JsonNode scalar(String value, JsonNode schema) {
    String type = schema.path("type").asText();
    if (type.equals("integer") && value.matches("-?\\d+")) {
      return LongNode.valueOf(Long.parseLong(value));
    }
    if (type.equals("number")) {
      try {
        return DoubleNode.valueOf(Double.parseDouble(value));
      } catch (NumberFormatException ignored) {
        return TextNode.valueOf(value);
      }
    }
    if (type.equals("boolean") && (value.equals("true") || value.equals("false"))) {
      return BooleanNode.valueOf(value.equals("true"));
    }
    return TextNode.valueOf(value);
  }

  /** The default answer of an operation, for tests that build their own pages. */
  public static ObjectNode page(List<String> ids, boolean hasMore) {
    ObjectNode out = ContractSpec.MAPPER.createObjectNode();
    out.put("object", "list");
    var data = out.putArray("data");
    for (String id : ids) {
      data.addObject().put("id", id);
    }
    out.put("has_more", hasMore);
    return out;
  }
}
