package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.billionsend.contract.ContractMock;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClientTest extends MockTestBase {
  static final Map<String, Object> SEND =
      Map.of(
          "from", "Acme <hello@example.com>",
          "to", List.of("person@example.com"),
          "subject", "Hi",
          "html", "<p>Hi</p>");

  static Map<String, Object> busy() {
    return ContractMock.error(503, "service_unavailable", "Busy");
  }

  // ------------------------------------------------------------------ configuration

  @Test
  void takesTheKeyAndDefaultsTheBaseUrl() {
    BillionSend client = BillionSend.builder().apiKey("bs_x").env(name -> null).build();
    assertEquals("https://api.billionsend.com", client.baseUrl());
    assertEquals(
        "https://example.com/api",
        BillionSend.builder().baseUrl("https://example.com/api///").env(n -> null).build().baseUrl());
  }

  @Test
  void readsTheKeyAndBaseUrlFromTheEnvironment() {
    Map<String, String> env = Map.of("BILLIONSEND_API_KEY", "bs_env_key", "BILLIONSEND_BASE_URL", mock.url() + "/");
    BillionSend client = BillionSend.builder().env(env::get).build();
    assertEquals(mock.url(), client.baseUrl());
    client.emails().get(ID);
    assertEquals("Bearer bs_env_key", mock.hits.get(0).header("authorization"));
  }

  @Test
  void throwsMissingApiKeyWithoutCallingTheApi() {
    BillionSend client = BillionSend.builder().baseUrl(mock.url()).env(n -> null).build();
    AuthenticationException error = assertThrows(AuthenticationException.class, () -> client.emails().get(ID));
    assertEquals("missing_api_key", error.getName());
    assertNull(error.getStatusCode());
    assertTrue(mock.hits.isEmpty());
  }

  @Test
  void letsAgentSignupRunWithoutAKeyAndNeverSendsOneToIt() {
    BillionSend keyless = BillionSend.builder().baseUrl(mock.url()).env(n -> null).build();
    keyless.agent().signup(sampleBody("POST /agent/signup"));
    client().agent().signup(sampleBody("POST /agent/signup"));
    BillionSend withHeader = builder().header("Authorization", "Bearer bs_leak").build();
    withHeader.agent().signup(sampleBody("POST /agent/signup"));
    assertEquals(3, mock.hits.size());
    mock.hits.forEach(hit -> assertNull(hit.header("authorization")));
  }

  @Test
  void sendsTheUserAgentJsonHeadersAndCustomHeaders() {
    BillionSend client = builder().header("x-client", "one").build();
    client.domains().create(Map.of("name", "example.com"), RequestOptions.builder().header("x-call", "two").build());
    ContractMock.Hit hit = mock.hits.get(0);
    assertEquals("billionsend-java/" + BillionSend.VERSION, hit.header("user-agent"));
    assertEquals("application/json", hit.header("accept"));
    assertEquals("application/json", hit.header("content-type"));
    assertEquals("one", hit.header("x-client"));
    assertEquals("two", hit.header("x-call"));
    assertEquals("{\"name\":\"example.com\"}", hit.body);
  }

  @Test
  void sendsANewIdempotencyKeyWithEveryCallOfASendingOperationAndNoneElsewhere() {
    BillionSend client = client();
    client.emails().send(SEND);
    client.emails().send(SEND);
    client.domains().create(Map.of("name", "example.com"));
    List<String> keys =
        mock.hitsFor("POST /emails").stream().map(h -> h.header("idempotency-key")).collect(Collectors.toList());
    assertEquals(2, keys.size());
    keys.forEach(k -> assertTrue(k.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), k));
    assertNotEquals(keys.get(0), keys.get(1));
    assertNull(mock.hitsFor("POST /domains").get(0).header("idempotency-key"));
  }

  @Test
  void usesAnInjectedHttpClient() {
    HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    builder().httpClient(http).build().usage().get();
    assertEquals(1, mock.hits.size());
  }

  @Test
  void refusesInvalidOptions() {
    assertThrows(IllegalArgumentException.class, () -> BillionSend.builder().maxRetries(-1));
    assertThrows(IllegalArgumentException.class, () -> BillionSend.builder().timeout(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> BillionSend.builder().baseUrl("not a url").env(n -> null).build());
    assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().maxRetries(-2));
    assertThrows(IllegalArgumentException.class, () -> EventStreamOptions.builder().maxReconnects(-1));
  }

  @Test
  void encodesPathParametersAndRefusesEmptyOnes() {
    BillionSend client = client();
    client.inboxes().get("support@example.com");
    client.contacts().get("a b/c?d");
    assertEquals("/inboxes/support%40example.com", mock.hits.get(0).rawPath);
    assertEquals("/contacts/a%20b%2Fc%3Fd", mock.hits.get(1).rawPath);
    BillionSendException error = assertThrows(BillionSendException.class, () -> client.emails().get(""));
    assertEquals("application_error", error.getName());
    assertEquals("Missing path parameter \"id\"", error.getMessage());
    assertThrows(BillionSendException.class, () -> client.emails().get(null));
    assertEquals(2, mock.hits.size());
  }

  // ------------------------------------------------------------------ errors

  @Nested
  class Errors {
    @Test
    void passesTheApiErrorResponseThroughWithTheRequestId() {
      mock.reply("GET /emails/{id}", 404, ContractMock.error(404, "not_found", "Email not found"));
      NotFoundException error = assertThrows(NotFoundException.class, () -> client().emails().get(ID));
      assertEquals(404, error.getStatusCode());
      assertEquals("not_found", error.getName());
      assertEquals("Email not found", error.getMessage());
      assertEquals("req_mock", error.getRequestId());
      assertInstanceOf(BillionSendException.class, error);
    }

    @Test
    void givesEachStatusItsErrorClass() {
      Map<Integer, Class<? extends BillionSendException>> classes =
          Map.of(
              400, ValidationException.class,
              422, ValidationException.class,
              401, AuthenticationException.class,
              403, PermissionDeniedException.class,
              404, NotFoundException.class,
              409, ConflictException.class,
              429, RateLimitException.class,
              500, ApiException.class,
              503, ApiException.class,
              410, BillionSendException.class);
      BillionSend client = builder().maxRetries(0).build();
      classes.forEach(
          (status, type) -> {
            mock.reply("POST /domains", status, ContractMock.error(status, "some_error", "No"));
            BillionSendException error =
                assertThrows(BillionSendException.class, () -> client.domains().create(Map.of("name", "example.com")));
            assertEquals(type, error.getClass(), "status " + status);
            assertEquals(status, error.getStatusCode());
          });
    }

    @Test
    void carriesRetryAfterOnARateLimitException() {
      mock.reply("POST /domains", 429, ContractMock.error(429, "rate_limit_exceeded", "Slow down"), Map.of("retry-after", "7"));
      RateLimitException error =
          assertThrows(RateLimitException.class, () -> client().domains().create(Map.of("name", "example.com")));
      assertEquals(7.0, error.getRetryAfter());
      assertEquals("rate_limit_exceeded", error.getName());
    }

    @Test
    void mapsANonJsonErrorBodyToApplicationError() {
      mock.replyText("POST /domains", 502, "<html>Bad gateway</html>", "text/html");
      ApiException error = assertThrows(ApiException.class, () -> client().domains().create(Map.of("name", "example.com")));
      assertEquals("application_error", error.getName());
      assertEquals("Unexpected 502 response: <html>Bad gateway</html>", error.getMessage());
      assertEquals(502, error.getStatusCode());
    }

    @Test
    void mapsAnUnreadableSuccessBodyToApplicationError() {
      mock.replyText("GET /usage", 200, "not json", "application/json");
      BillionSendException error = assertThrows(BillionSendException.class, () -> client().usage().get());
      assertEquals(BillionSendException.class, error.getClass());
      assertEquals("application_error", error.getName());
      assertEquals(200, error.getStatusCode());
      assertEquals("req_mock", error.getRequestId());
    }

    @Test
    void mapsANetworkFailureToApiConnectionExceptionWithNoStatus() throws Exception {
      int port;
      try (ServerSocket socket = new ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      BillionSend client = builder().baseUrl("http://127.0.0.1:" + port).maxRetries(0).build();
      ApiConnectionException error = assertThrows(ApiConnectionException.class, () -> client.usage().get());
      assertNull(error.getStatusCode());
      assertEquals("application_error", error.getName());
      assertTrue(error.getMessage().startsWith("Network error: "), error.getMessage());
    }

    @Test
    void timesOut() {
      mock.replyAfter("GET /usage", 1_000);
      BillionSend client = builder().timeout(Duration.ofMillis(100)).maxRetries(0).build();
      ApiTimeoutException error = assertThrows(ApiTimeoutException.class, () -> client.usage().get());
      assertEquals("Request timed out after 100 ms", error.getMessage());
      assertInstanceOf(ApiConnectionException.class, error);
    }

    @Test
    void endsQuietlyWhenTheThreadIsInterrupted() {
      mock.replyAfter("GET /usage", 1_000);
      Thread.currentThread().interrupt();
      BillionSendException error = assertThrows(BillionSendException.class, () -> client().usage().get());
      assertTrue(Thread.interrupted(), "the interrupt is kept");
      assertEquals("Request was interrupted", error.getMessage());
      assertNull(error.getStatusCode());
    }
  }

  // ------------------------------------------------------------------ retries

  @Nested
  class Retries {
    @Test
    void retriesAnIdempotentRequestOn5xxAnd429HonouringRetryAfter() {
      mock.reply("GET /emails/{id}", 503, busy(), Map.of("retry-after", "0"));
      mock.reply("GET /emails/{id}", 429, ContractMock.error(429, "rate_limit_exceeded", "Slow down"), Map.of("retry-after", "2"));
      JsonNode email = client().emails().get(ID);
      assertTrue(email.isObject());
      assertEquals(3, mock.hitsFor("GET /emails/{id}").size());
      assertEquals(List.of(0L, 2000L), sleeps);
    }

    @Test
    void throwsTheLastErrorOnceTheRetriesAreSpent() {
      for (int i = 0; i < 3; i++) {
        mock.reply("DELETE /emails/{id}", 503, busy(), Map.of("retry-after", "0"));
      }
      ApiException error = assertThrows(ApiException.class, () -> client().emails().delete(ID));
      assertEquals(503, error.getStatusCode());
      assertEquals(3, mock.hitsFor("DELETE /emails/{id}").size());
    }

    @Test
    void neverRetriesAPostThatDoesNotHonourIdempotencyKey() {
      mock.reply("POST /domains", 503, busy(), Map.of("retry-after", "0"));
      assertThrows(ApiException.class, () -> client().domains().create(Map.of("name", "example.com")));
      assertEquals(1, mock.hitsFor("POST /domains").size());
    }

    @Test
    void retriesASendWithTheKeyItMadeForTheCallOrTheCallers() {
      mock.reply("POST /emails", 503, busy(), Map.of("retry-after", "0"));
      client().emails().send(SEND);
      List<String> made =
          mock.hitsFor("POST /emails").stream().map(h -> h.header("idempotency-key")).collect(Collectors.toList());
      assertEquals(2, made.size());
      assertEquals(made.get(0), made.get(1));

      mock.reset();
      mock.reply("POST /emails", 503, busy(), Map.of("retry-after", "0"));
      client().emails().send(SEND, RequestOptions.builder().idempotencyKey("order-42").build());
      assertEquals(
          List.of("order-42", "order-42"),
          mock.hitsFor("POST /emails").stream().map(h -> h.header("idempotency-key")).collect(Collectors.toList()));
    }

    @Test
    void retriesASendWhoseFirstAttemptIsStillRunningOnly() {
      Map<String, Object> reply = Map.of("from", "a@example.com", "text", "Thanks");
      mock.reply(
          "POST /emails/receiving/{id}/reply",
          409,
          ContractMock.error(409, "concurrent_idempotent_requests", "Still processing"),
          Map.of("retry-after", "0"));
      client().emails().receiving().reply(ID, reply);
      assertEquals(2, mock.hitsFor("POST /emails/receiving/{id}/reply").size());

      mock.reset();
      mock.reply(
          "POST /emails/receiving/{id}/reply",
          409,
          ContractMock.error(409, "invalid_idempotent_request", "Different payload"),
          Map.of("retry-after", "0"));
      ConflictException error =
          assertThrows(ConflictException.class, () -> client().emails().receiving().reply(ID, reply));
      assertEquals("invalid_idempotent_request", error.getName());
      assertEquals(1, mock.hitsFor("POST /emails/receiving/{id}/reply").size());

      mock.reset();
      mock.reply("DELETE /emails/{id}", 409, ContractMock.error(409, "concurrent_idempotent_requests", "Busy"));
      assertThrows(ConflictException.class, () -> client().emails().delete(ID));
      assertEquals(1, mock.hitsFor("DELETE /emails/{id}").size());
    }

    @Test
    void retriesASendAfterANetworkFailureWithTheSameKey() {
      mock.drop("POST /emails");
      client().emails().send(SEND);
      List<ContractMock.Hit> hits = mock.hitsFor("POST /emails");
      assertEquals(2, hits.size());
      assertEquals(hits.get(0).header("idempotency-key"), hits.get(1).header("idempotency-key"));
      assertEquals(1, sleeps.size());
      assertTrue(sleeps.get(0) >= 375 && sleeps.get(0) <= 500, "backoff " + sleeps);
    }

    @Test
    void doesNotRetry4xxAnswersOtherThan429() {
      mock.reply("GET /emails/{id}", 400, ContractMock.error(400, "validation_error", "Bad id"));
      assertThrows(ValidationException.class, () -> client().emails().get(ID));
      assertEquals(1, mock.hitsFor("GET /emails/{id}").size());
    }

    @Test
    void doesNotWaitForARetryAfterLongerThanAMinute() {
      mock.reply("GET /emails/{id}", 429, ContractMock.error(429, "rate_limit_exceeded", "Later"), Map.of("retry-after", "120"));
      RateLimitException error = assertThrows(RateLimitException.class, () -> client().emails().get(ID));
      assertEquals(120.0, error.getRetryAfter());
      assertEquals(1, mock.hitsFor("GET /emails/{id}").size());
      assertTrue(sleeps.isEmpty());
    }

    @Test
    void respectsMaxRetriesPerClientAndPerRequest() {
      for (int i = 0; i < 6; i++) {
        mock.reply("GET /emails/{id}", 503, busy(), Map.of("retry-after", "0"));
      }
      assertThrows(ApiException.class, () -> builder().maxRetries(0).build().emails().get(ID));
      assertEquals(1, mock.hitsFor("GET /emails/{id}").size());
      assertThrows(
          ApiException.class,
          () -> builder().maxRetries(0).build().emails().get(ID, RequestOptions.builder().maxRetries(4).build()));
      assertEquals(6, mock.hitsFor("GET /emails/{id}").size());
    }

    @Test
    void retriesATimedOutIdempotentRequest() {
      mock.replyAfter("GET /usage", 1_000);
      AtomicInteger attempts = new AtomicInteger();
      JsonNode usage =
          builder().timeout(Duration.ofMillis(300)).build().usage().get(RequestOptions.builder().maxRetries(1).build());
      attempts.set(mock.hitsFor("GET /usage").size());
      assertTrue(usage.isObject());
      assertEquals(2, attempts.get());
    }

    @Test
    void sendsAnIdempotencyKeyOnAnyRequestWhenTheCallerGivesOneAndRetriesIt() {
      mock.reply("POST /domains", 503, busy(), Map.of("retry-after", "0"));
      client().domains().create(Map.of("name", "example.com"), RequestOptions.builder().idempotencyKey("domain-1").build());
      assertEquals(
          List.of("domain-1", "domain-1"),
          mock.hitsFor("POST /domains").stream().map(h -> h.header("idempotency-key")).collect(Collectors.toList()));
    }
  }
}
