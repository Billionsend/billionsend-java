package com.billionsend;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The README's examples, kept here so the build fails when they drift from the SDK ({@link
 * ReadmeTest} checks that every Java block of the README appears here). Never executed.
 */
@SuppressWarnings("unused")
final class ReadmeExamples {
  private final BillionSend client = new BillionSend("bs_...");

  private static String draftAnswer(String subject, String text) {
    return "";
  }

  void quickStart() {
    BillionSend client = new BillionSend(); // reads BILLIONSEND_API_KEY
    BillionSend explicit = new BillionSend("bs_...");

    JsonNode sent =
        client.emails().send(
            Map.of(
                "from", "Acme <hello@acme.dev>",
                "to", List.of("ana@example.com"),
                "subject", "Welcome to Acme",
                "html", "<p>Glad you are here.</p>"));
    System.out.println("queued " + sent.get("id").asText());
  }

  void agentSignup() {
    JsonNode signup =
        new BillionSend()
            .agent()
            .signup(Map.of("human_email", "owner@acme.dev", "name", "Support agent"));

    BillionSend agent = new BillionSend(signup.get("api_key").asText());
    System.out.println(signup.path("inbox").path("email_address").asText());
  }

  void sendingOnce(Map<String, Object> message, String userId) {
    client.emails().send(message, RequestOptions.builder().idempotencyKey("welcome/" + userId).build());
  }

  void agentLoop() {
    String inboxId = "support@acme.dev"; // an inbox id or its address

    try (EventStream stream =
        client.inboxes().events().streamInbox(inboxId, Map.of("types", "inbox.email.received"))) {
      for (InboxStreamItem item : stream) {
        if (item instanceof StreamGapEvent) {
          // Resumed from too far back: events may be missing. Re-read the threads.
          continue;
        }
        JsonNode ids = item.data().path("data");
        String threadId = ids.path("thread_id").asText(null);
        String emailId = ids.path("email_id").asText(null);
        if (threadId == null || emailId == null) {
          continue;
        }

        JsonNode email = client.inboxes().threads().emails().get(inboxId, threadId, emailId);
        String answer = draftAnswer(email.path("subject").asText(), email.path("text").asText()); // your model
        client
            .inboxes()
            .threads()
            .emails()
            .reply(
                inboxId,
                threadId,
                emailId,
                Map.of("text", answer),
                // a replayed event cannot reply twice
                RequestOptions.builder().idempotencyKey("reply/" + emailId).build());
      }
    }
  }

  void codes() {
    JsonNode code = client.inboxes().codes().latest("support@acme.dev", Map.of("from", "example.com", "wait", 30));
  }

  void liveEvents(String saved) {
    EventStreamOptions options =
        EventStreamOptions.builder()
            .lastEventId(saved) // resume where a previous run stopped
            .build();
    try (EventStream stream =
        client.inboxes().events().stream(Map.of("types", "inbox.email.received,inbox.thread.created"), options)) {
      for (InboxStreamItem item : stream) {
        saved = stream.lastEventId();
        // break, or stream.close(), ends the stream
      }
    }
  }

  void pagination() {
    for (JsonNode contact : client.contacts().listAll(Map.of("limit", 100))) {
      System.out.println(contact.get("email").asText());
    }

    List<String> domainIds =
        client.domains().listAll().stream().map(d -> d.get("id").asText()).collect(Collectors.toList());
  }

  void errors(String id) {
    try {
      JsonNode email = client.emails().get(id);
    } catch (NotFoundException e) {
      // no such email
    } catch (RateLimitException e) {
      System.out.println("retry in " + e.getRetryAfter() + " s");
    } catch (BillionSendException e) {
      System.err.println(e.getStatusCode() + " " + e.getName() + " " + e.getMessage() + " " + e.getRequestId());
    }
  }

  void retries(Map<String, Object> message) {
    BillionSend patient = BillionSend.builder().maxRetries(4).timeout(Duration.ofSeconds(120)).build();
    client.emails().send(message, RequestOptions.builder().maxRetries(0).timeout(Duration.ofSeconds(10)).build());
  }

  int handle(byte[] rawBody, Map<String, List<String>> headers) {
    try {
      JsonNode event = WebhookVerifier.verify(rawBody, headers, System.getenv("BILLIONSEND_WEBHOOK_SECRET"));
      if (event.get("type").asText().equals("email.bounced")) {
        // ...
      }
      return 204;
    } catch (WebhookVerificationException e) {
      return 400;
    }
  }

  void configuration() {
    BillionSend configured =
        BillionSend.builder()
            .apiKey("bs_...") // default: BILLIONSEND_API_KEY
            .baseUrl("https://api.billionsend.com") // default: BILLIONSEND_BASE_URL, then this
            .timeout(Duration.ofSeconds(60)) // per attempt; event streams are timed only until they open
            .maxRetries(2) // see Retries and timeouts
            .header("x-my-header", "value") // sent with every request
            .httpClient(HttpClient.newHttpClient()) // default: a new one that does not follow redirects
            .build();
  }
}
