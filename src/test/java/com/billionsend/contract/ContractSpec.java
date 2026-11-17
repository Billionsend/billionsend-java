package com.billionsend.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** The OpenAPI snapshot, read for the tests: operations, path matching, samples and validation. */
public final class ContractSpec {
  public static final ObjectMapper MAPPER = new ObjectMapper();
  public static final JsonNode DOC = load();
  public static final String SAMPLE_UUID = "6f1c2a1e-1b7a-4c51-9e43-2f2d7a9c0b11";
  private static final Set<String> METHODS = Set.of("get", "put", "post", "patch", "delete");
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** Every operation, keyed {@code "METHOD /path"}. */
  public static final Map<String, Operation> OPERATIONS = operations();

  private ContractSpec() {}

  private static JsonNode load() {
    try {
      return MAPPER.readTree(Files.readAllBytes(Path.of("openapi", "api.openapi.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** One operation of the snapshot. */
  public static final class Operation {
    public final String key;
    public final String method;
    public final String path;
    public final JsonNode node;
    /** The JSON pointer of the operation in the snapshot. */
    public final String pointer;
    final Pattern pattern;
    final int literalSegments;

    Operation(String method, String path, JsonNode node) {
      this.method = method;
      this.path = path;
      this.key = method + " " + path;
      this.node = node;
      this.pointer = "/paths/" + escapePointer(path) + "/" + method.toLowerCase();
      StringBuilder regex = new StringBuilder("^");
      int literals = 0;
      for (String segment : path.substring(1).split("/")) {
        regex.append('/');
        if (segment.startsWith("{")) {
          regex.append("[^/]+");
        } else {
          regex.append(Pattern.quote(segment));
          literals++;
        }
      }
      this.pattern = Pattern.compile(regex.append('$').toString());
      this.literalSegments = literals;
    }

    public String operationId() {
      return node.path("operationId").asText();
    }

    public List<JsonNode> parameters(String where) {
      List<JsonNode> out = new ArrayList<>();
      for (JsonNode p : node.path("parameters")) {
        if (p.path("in").asText().equals(where)) {
          out.add(p);
        }
      }
      return out;
    }

    public boolean needsAuth() {
      JsonNode security = node.get("security");
      return security == null || security.size() > 0;
    }

    public String requestPointer() {
      return pointer + "/requestBody/content/application~1json/schema";
    }

    public String responsePointer(String status) {
      return pointer + "/responses/" + status + "/content/application~1json/schema";
    }

    /** The parameters declared in {@code where}, with their index in the operation's list. */
    public Map<Integer, JsonNode> indexedParameters(String where) {
      Map<Integer, JsonNode> out = new LinkedHashMap<>();
      JsonNode all = node.path("parameters");
      for (int i = 0; i < all.size(); i++) {
        if (all.get(i).path("in").asText().equals(where)) {
          out.put(i, all.get(i));
        }
      }
      return out;
    }

    public JsonNode requestSchema() {
      return node.path("requestBody").path("content").path("application/json").path("schema");
    }

    public boolean hasBody() {
      return node.has("requestBody");
    }

    public boolean bodyRequired() {
      return node.path("requestBody").path("required").asBoolean(false);
    }

    /** The lowest 2xx status with a JSON body, or {@code null}. */
    public String successStatus() {
      Set<String> statuses = new TreeSet<>();
      node.path("responses")
          .fieldNames()
          .forEachRemaining(
              s -> {
                if (s.matches("2\\d\\d")
                    && node.path("responses").path(s).path("content").has("application/json")) {
                  statuses.add(s);
                }
              });
      return statuses.isEmpty() ? null : statuses.iterator().next();
    }

    public boolean isStream() {
      for (JsonNode response : node.path("responses")) {
        if (response.path("content").has("text/event-stream")) {
          return true;
        }
      }
      return false;
    }

    public JsonNode responseSchema(String status) {
      return node.path("responses").path(status).path("content").path("application/json").path("schema");
    }

    public boolean idempotent() {
      return parameters("header").stream()
          .anyMatch(p -> p.path("name").asText().equalsIgnoreCase("idempotency-key"));
    }

    /** Whether it is a cursor list: {@code after} and {@code before} in the query. */
    public boolean cursorList() {
      Set<String> names = new TreeSet<>();
      parameters("query").forEach(p -> names.add(p.path("name").asText()));
      return names.contains("after") && names.contains("before") && !isStream();
    }
  }

  private static Map<String, Operation> operations() {
    Map<String, Operation> out = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> path : DOC.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
        if (METHODS.contains(method.getKey())) {
          Operation op =
              new Operation(method.getKey().toUpperCase(), path.getKey(), method.getValue());
          out.put(op.key, op);
        }
      }
    }
    return Collections.unmodifiableMap(out);
  }

  public static Operation operation(String key) {
    Operation op = OPERATIONS.get(key);
    if (op == null) {
      throw new IllegalArgumentException("No operation " + key);
    }
    return op;
  }

  /** The operation a request reaches; literal segments win over parameters. */
  public static Operation match(String method, String rawPath) {
    Operation best = null;
    for (Operation op : OPERATIONS.values()) {
      if (op.method.equals(method) && op.pattern.matcher(rawPath).matches()) {
        if (best == null || op.literalSegments > best.literalSegments) {
          best = op;
        }
      }
    }
    return best;
  }

  // ------------------------------------------------------------------ schemas

  public static JsonNode resolve(JsonNode schema) {
    JsonNode current = schema;
    for (int guard = 0; current.has("$ref") && guard < 32; guard++) {
      String name = current.get("$ref").asText().replace("#/components/schemas/", "");
      current = DOC.path("components").path("schemas").path(name);
      if (current.isMissingNode()) {
        throw new IllegalStateException("Unresolvable $ref " + name);
      }
    }
    return current;
  }

  private static List<String> types(JsonNode schema) {
    List<String> out = new ArrayList<>();
    JsonNode type = schema.get("type");
    if (type == null) {
      if (schema.has("properties")) {
        out.add("object");
      }
    } else if (type.isArray()) {
      type.forEach(t -> out.add(t.asText()));
    } else {
      out.add(type.asText());
    }
    return out;
  }

  /**
   * A deterministic instance of {@code schema}: objects carry every property when {@code full},
   * otherwise only the required ones; one item per array.
   */
  public static JsonNode example(JsonNode input, boolean full) {
    return example(input, full, 0);
  }

  private static JsonNode example(JsonNode input, boolean full, int depth) {
    JsonNode schema = resolve(input);
    if (schema.has("const")) {
      return schema.get("const");
    }
    if (schema.path("enum").isArray()) {
      for (JsonNode value : schema.get("enum")) {
        if (!value.isNull()) {
          return value;
        }
      }
      return NODES.nullNode();
    }
    for (String key : List.of("oneOf", "anyOf")) {
      if (schema.path(key).isArray()) {
        JsonNode preferred = schema.get(key).get(0);
        for (JsonNode option : schema.get(key)) {
          if (!types(resolve(option)).equals(List.of("null"))) {
            preferred = option;
            break;
          }
        }
        return example(preferred, full, depth + 1);
      }
    }
    if (schema.path("allOf").isArray()) {
      ObjectNode out = NODES.objectNode();
      for (JsonNode part : schema.get("allOf")) {
        JsonNode piece = example(part, full, depth + 1);
        if (piece.isObject()) {
          out.setAll((ObjectNode) piece);
        } else {
          return piece;
        }
      }
      return out;
    }
    List<String> types = types(schema);
    String type =
        types.stream().filter(t -> !t.equals("null")).findFirst().orElse(types.isEmpty() ? "" : "null");
    switch (type) {
      case "object":
        {
          ObjectNode out = NODES.objectNode();
          Set<String> required = new TreeSet<>();
          schema.path("required").forEach(name -> required.add(name.asText()));
          for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
            if (full || required.contains(property.getKey())) {
              out.set(property.getKey(), example(property.getValue(), full, depth + 1));
            }
          }
          for (String name : required) {
            if (!out.has(name)) {
              out.put(name, "value");
            }
          }
          return out;
        }
      case "array":
        {
          ArrayNode out = NODES.arrayNode();
          int count = Math.max(1, schema.path("minItems").asInt(1));
          if (schema.has("maxItems")) {
            count = Math.min(count, schema.get("maxItems").asInt());
          }
          if (depth > 8) {
            count = Math.min(count, schema.path("minItems").asInt(0));
          }
          for (int i = 0; i < count; i++) {
            out.add(example(schema.path("items"), full, depth + 1));
          }
          return out;
        }
      case "string":
        return NODES.textNode(exampleString(schema));
      case "integer":
        {
          long value = schema.has("minimum") ? (long) Math.ceil(schema.get("minimum").asDouble()) : 1;
          if (schema.has("maximum")) {
            value = Math.min(value, schema.get("maximum").asLong());
          }
          return NODES.numberNode(value);
        }
      case "number":
        return NODES.numberNode(schema.has("minimum") ? schema.get("minimum").asDouble() : 1.0);
      case "boolean":
        return NODES.booleanNode(false);
      case "null":
        return NODES.nullNode();
      default:
        return NODES.objectNode();
    }
  }

  private static String exampleString(JsonNode schema) {
    switch (schema.path("format").asText()) {
      case "uuid":
        return SAMPLE_UUID;
      case "date-time":
        return "2026-11-16T12:00:00.000Z";
      case "date":
        return "2026-11-16";
      case "email":
        return "person@example.com";
      case "uri":
      case "url":
        return "https://example.com/resource";
      default:
        break;
    }
    String value = "value";
    if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText()).matcher(value).find()) {
      value = "a";
    }
    int min = schema.path("minLength").asInt(0);
    int max = schema.has("maxLength") ? schema.get("maxLength").asInt() : Integer.MAX_VALUE;
    StringBuilder out = new StringBuilder(value);
    while (out.length() < min) {
      out.append('x');
    }
    return out.length() > max ? out.substring(0, max) : out.toString();
  }

  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final Map<String, JsonSchema> VALIDATORS = new ConcurrentHashMap<>();

  /** A JSON pointer segment, escaped. */
  public static String escapePointer(String segment) {
    return segment.replace("~", "~0").replace("/", "~1");
  }

  /**
   * The errors of {@code value} against the schema at {@code pointer} in the snapshot (JSON Schema
   * 2020-12, as the document declares); empty when valid.
   */
  public static List<String> validate(String pointer, JsonNode value) {
    JsonSchema schema =
        VALIDATORS.computeIfAbsent(
            pointer,
            at -> {
              ObjectNode root = DOC.deepCopy();
              root.put("$ref", "#" + at.replace("{", "%7B").replace("}", "%7D"));
              return FACTORY.getSchema(root);
            });
    List<String> errors = new ArrayList<>();
    for (ValidationMessage message : schema.validate(value)) {
      errors.add(message.getMessage());
    }
    return errors;
  }
}
