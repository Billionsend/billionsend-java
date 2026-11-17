package com.billionsend;

import com.billionsend.internal.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies webhook deliveries.
 *
 * <p>The signature is HMAC-SHA256 over {@code <webhook-id>.<webhook-timestamp>.<body>} with the
 * endpoint's {@code whsec_} secret, sent as {@code v1,<base64>} in {@code webhook-signature};
 * during a secret rotation the header carries one signature per secret, space-separated, and any of
 * them matching is enough. The timestamp (Unix seconds) must be within the tolerance of now (five
 * minutes by default), so a captured delivery cannot be replayed later.
 *
 * <p>Pass the body exactly as it arrived (the raw text or bytes, before any JSON parsing):
 * re-serialised JSON does not verify.
 *
 * <pre>{@code
 * JsonNode event = WebhookVerifier.verify(rawBody, headers, System.getenv("BILLIONSEND_WEBHOOK_SECRET"));
 * if (event.get("type").asText().equals("email.bounced")) { ... }
 * }</pre>
 */
public final class WebhookVerifier {
  /** The default tolerance of the {@code webhook-timestamp}. */
  public static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(300);

  private static final String SECRET_PREFIX = "whsec_";
  private static final int SECRET_MIN_BYTES = 24;
  private static final int SECRET_MAX_BYTES = 64;

  private WebhookVerifier() {}

  /**
   * Verifies a delivery against the current time and returns its parsed body.
   *
   * @param payload the raw body
   * @param headers the request headers, names in any case; a value may be a {@code String}, a
   *     {@code Collection} or a {@code String[]} (the first value counts)
   * @param secret the endpoint's {@code whsec_} secret
   * @return the parsed body: {@code {type, created_at, data}}
   * @throws WebhookVerificationException when a header is missing, the timestamp is out of
   *     tolerance, no signature matches, the secret is malformed, or the body is not JSON
   */
  public static JsonNode verify(String payload, Map<String, ?> headers, String secret) {
    return verify(payload.getBytes(StandardCharsets.UTF_8), headers, secret);
  }

  /**
   * Verifies a delivery against the current time and returns its parsed body.
   *
   * @param payload the raw body
   * @param headers the request headers (see {@link #verify(String, Map, String)})
   * @param secret the endpoint's {@code whsec_} secret
   * @return the parsed body
   * @throws WebhookVerificationException when the delivery does not verify
   */
  public static JsonNode verify(byte[] payload, Map<String, ?> headers, String secret) {
    return verify(payload, headers, secret, DEFAULT_TOLERANCE, Instant.now());
  }

  /**
   * Verifies a delivery and returns its parsed body.
   *
   * @param payload the raw body
   * @param headers the request headers (see {@link #verify(String, Map, String)})
   * @param secret the endpoint's {@code whsec_} secret
   * @param tolerance how far the {@code webhook-timestamp} may be from {@code now}
   * @param now the time to check the timestamp against
   * @return the parsed body
   * @throws WebhookVerificationException when the delivery does not verify
   */
  public static JsonNode verify(
      byte[] payload, Map<String, ?> headers, String secret, Duration tolerance, Instant now) {
    String id = header(headers, "webhook-id");
    String timestamp = header(headers, "webhook-timestamp");
    String signature = header(headers, "webhook-signature");
    if (isEmpty(id) || isEmpty(timestamp) || isEmpty(signature)) {
      throw new WebhookVerificationException(
          "Missing the webhook-id, webhook-timestamp or webhook-signature header");
    }
    if (!timestamp.matches("\\d+") || timestamp.length() > 18) {
      throw new WebhookVerificationException("Invalid webhook-timestamp header");
    }
    long seconds = Long.parseLong(timestamp);
    long current = now.getEpochSecond();
    long allowed = tolerance.getSeconds();
    if (current - seconds > allowed) {
      throw new WebhookVerificationException("The webhook timestamp is too old");
    }
    if (seconds - current > allowed) {
      throw new WebhookVerificationException("The webhook timestamp is too new");
    }

    byte[] expected;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secretKey(secret), "HmacSHA256"));
      mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
      expected = mac.doFinal(payload);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }

    boolean matched = false;
    for (String candidate : signature.split(" ")) {
      int comma = candidate.indexOf(',');
      if (comma == -1 || !candidate.substring(0, comma).equals("v1")) {
        continue;
      }
      byte[] given = decodeBase64(candidate.substring(comma + 1));
      if (given != null && MessageDigest.isEqual(given, expected)) {
        matched = true;
      }
    }
    if (!matched) {
      throw new WebhookVerificationException("No webhook signature matches");
    }

    try {
      JsonNode body = Json.MAPPER.readTree(payload);
      if (body == null || body.isMissingNode()) {
        throw new WebhookVerificationException("The webhook body is not JSON");
      }
      return body;
    } catch (IOException e) {
      throw new WebhookVerificationException("The webhook body is not JSON");
    }
  }

  private static boolean isEmpty(String value) {
    return value == null || value.isEmpty();
  }

  private static String header(Map<String, ?> headers, String name) {
    for (Map.Entry<String, ?> entry : headers.entrySet()) {
      if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase(name)) {
        continue;
      }
      Object value = entry.getValue();
      if (value instanceof Collection<?>) {
        Collection<?> values = (Collection<?>) value;
        return values.isEmpty() ? null : String.valueOf(values.iterator().next());
      }
      if (value instanceof String[]) {
        String[] values = (String[]) value;
        return values.length == 0 ? null : values[0];
      }
      return value == null ? null : value.toString();
    }
    return null;
  }

  private static byte[] decodeBase64(String text) {
    try {
      return Base64.getDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The key of a {@code whsec_} secret: canonical base64 of 24 to 64 bytes. */
  private static byte[] secretKey(String secret) {
    String encoded =
        secret != null && secret.startsWith(SECRET_PREFIX)
            ? secret.substring(SECRET_PREFIX.length())
            : null;
    byte[] key = encoded == null ? null : decodeBase64(encoded);
    if (key == null
        || key.length < SECRET_MIN_BYTES
        || key.length > SECRET_MAX_BYTES
        || !Base64.getEncoder().encodeToString(key).equals(encoded)) {
      throw new WebhookVerificationException("The webhook secret is not a whsec_ secret");
    }
    return key;
  }
}
