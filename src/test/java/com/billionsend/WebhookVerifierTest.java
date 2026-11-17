package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class WebhookVerifierTest {
  // The known-answer vector the API's own signing test pins.
  static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
  static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
  static final String TIMESTAMP = "1614265330";
  static final String PAYLOAD = "{\"test\": 2432232314}";
  static final String SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";
  static final Instant AT = Instant.ofEpochSecond(Long.parseLong(TIMESTAMP));
  static final Duration TOLERANCE = WebhookVerifier.DEFAULT_TOLERANCE;
  static final SecureRandom RANDOM = new SecureRandom();

  static Map<String, Object> headersOf(String signature, String timestamp) {
    Map<String, Object> headers = new HashMap<>();
    headers.put("webhook-id", ID);
    headers.put("webhook-timestamp", timestamp);
    headers.put("webhook-signature", signature);
    return headers;
  }

  static Map<String, Object> headersOf() {
    return headersOf(SIGNATURE, TIMESTAMP);
  }

  static String randomSecret(int bytes) {
    byte[] key = new byte[bytes];
    RANDOM.nextBytes(key);
    return "whsec_" + Base64.getEncoder().encodeToString(key);
  }

  /** Signs as the API does. */
  static String sign(String secret, String id, String timestamp, String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring(6)), "HmacSHA256"));
    byte[] digest = mac.doFinal((id + "." + timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
    return "v1," + Base64.getEncoder().encodeToString(digest);
  }

  static JsonNode verify(String payload, Map<String, ?> headers, String secret, Instant now) {
    return WebhookVerifier.verify(payload.getBytes(StandardCharsets.UTF_8), headers, secret, TOLERANCE, now);
  }

  static WebhookVerificationException failure(Executable call) {
    WebhookVerificationException error = assertThrows(WebhookVerificationException.class, call);
    assertTrue(error instanceof BillionSendException);
    return error;
  }

  @Test
  void verifiesThePublishedVectorAndReturnsTheParsedBody() {
    JsonNode event = verify(PAYLOAD, headersOf(), SECRET, AT);
    assertEquals(2432232314L, event.get("test").asLong());
    assertEquals(1, event.size());
  }

  @Test
  void takesTheBodyAsTextOrBytesAndTheHeadersInAnyCaseAndShape() {
    Map<String, Object> headers = new HashMap<>();
    headers.put("Webhook-Id", ID);
    headers.put("WEBHOOK-TIMESTAMP", List.of(TIMESTAMP));
    headers.put("webhook-signature", new String[] {SIGNATURE});
    byte[] bytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
    assertEquals(2432232314L, WebhookVerifier.verify(bytes, headers, SECRET, TOLERANCE, AT).get("test").asLong());
    assertEquals(2432232314L, verify(PAYLOAD, headers, SECRET, AT).get("test").asLong());
  }

  @Test
  void acceptsAnyOfTheSignaturesARotationSendsAndRefusesAV2OrAWrongOne() throws Exception {
    String other = randomSecret(32);
    String rotated = sign(other, ID, TIMESTAMP, PAYLOAD) + " " + SIGNATURE;
    verify(PAYLOAD, headersOf(rotated, TIMESTAMP), SECRET, AT);
    verify(PAYLOAD, headersOf(rotated, TIMESTAMP), other, AT);

    String wrongVersion = SIGNATURE.replace("v1,", "v2,");
    assertEquals(
        "No webhook signature matches",
        failure(() -> verify(PAYLOAD, headersOf(wrongVersion, TIMESTAMP), SECRET, AT)).getMessage());
    failure(() -> verify(PAYLOAD + " ", headersOf(), SECRET, AT));
    failure(() -> verify(PAYLOAD, headersOf(), other, AT));
    failure(() -> verify(PAYLOAD, headersOf("v1,not base64!", TIMESTAMP), SECRET, AT));
  }

  @Test
  void refusesATimestampOutsideTheToleranceEitherWay() {
    Instant late = AT.plusSeconds(301);
    Instant early = AT.minusSeconds(301);
    assertTrue(failure(() -> verify(PAYLOAD, headersOf(), SECRET, late)).getMessage().contains("too old"));
    assertTrue(failure(() -> verify(PAYLOAD, headersOf(), SECRET, early)).getMessage().contains("too new"));
    WebhookVerifier.verify(PAYLOAD.getBytes(StandardCharsets.UTF_8), headersOf(), SECRET, Duration.ofSeconds(600), late);
    failure(() -> verify(PAYLOAD, headersOf(SIGNATURE, "16142e5"), SECRET, AT));
    failure(() -> verify(PAYLOAD, headersOf(SIGNATURE, "99999999999999999999999"), SECRET, AT));
  }

  @Test
  void verifiesAgainstTheCurrentTimeByDefault() throws Exception {
    String secret = randomSecret(24);
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String payload = "{\"type\":\"inbox.email.received\",\"created_at\":\"2026-11-16T00:00:00.000Z\",\"data\":{}}";
    Map<String, Object> headers = new HashMap<>();
    headers.put("webhook-id", "msg_1");
    headers.put("webhook-timestamp", timestamp);
    headers.put("webhook-signature", sign(secret, "msg_1", timestamp, payload));
    JsonNode event = WebhookVerifier.verify(payload, headers, secret);
    assertEquals("inbox.email.received", event.get("type").asText());
  }

  @Test
  void refusesMissingHeadersAMalformedSecretAndABodyThatIsNotJson() throws Exception {
    Map<String, Object> unsigned = headersOf();
    unsigned.remove("webhook-signature");
    failure(() -> verify(PAYLOAD, unsigned, SECRET, AT));
    for (String secret :
        List.of(
            "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw",
            "whsec_short",
            randomSecret(65),
            "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaS")) {
      assertEquals(
          "The webhook secret is not a whsec_ secret",
          failure(() -> verify(PAYLOAD, headersOf(), secret, AT)).getMessage(),
          secret);
    }
    String text = "not json";
    String signature = sign(SECRET, ID, TIMESTAMP, text);
    WebhookVerificationException error = failure(() -> verify(text, headersOf(signature, TIMESTAMP), SECRET, AT));
    assertEquals("webhook_verification_error", error.getName());
    assertNull(error.getStatusCode());
    assertNull(error.getRequestId());
  }
}
