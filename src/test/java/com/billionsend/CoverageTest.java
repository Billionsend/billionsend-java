package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.billionsend.contract.ContractMock;
import com.billionsend.contract.ContractSpec;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every operation in the OpenAPI snapshot must be reachable through exactly one SDK call in this
 * table, and every call must hit the operation it claims. A new operation in the snapshot without
 * an entry here fails the build; so does an SDK call that reaches an endpoint the snapshot does
 * not have.
 */
class CoverageTest extends MockTestBase {
  static final String ID3 = "1e6c1b63-9d20-4b5f-81a5-8e9f0b2c3d4e";
  static final String INBOX = "support@example.com";
  static final Map<String, Object> SEND =
      Map.of(
          "from", "Acme <hello@example.com>",
          "to", List.of("person@example.com"),
          "subject", "Hi",
          "html", "<p>Hi</p>");

  static final Map<String, Function<BillionSend, Object>> CALLS = calls();
  static final Map<Boolean, Set<String>> REACHED = Map.of(true, new TreeSet<>(), false, new TreeSet<>());

  private static Map<String, Function<BillionSend, Object>> calls() {
    Map<String, Function<BillionSend, Object>> calls = new LinkedHashMap<>();
    calls.put("GET /emails/metrics", bs ->
            bs.emails()
                .metrics()
                .get(
                    Map.of(
                        "metrics", List.of("delivered", "opened"),
                        "dimensions", List.of("period"),
                        "granularity", "daily")));
    calls.put("GET /emails/metrics/links", bs -> bs.emails().metrics().links(Map.of("limit", 5)));
    calls.put("GET /emails/metrics/countries", bs -> bs.emails().metrics().countries());
    calls.put("GET /emails/metrics/clients", bs -> bs.emails().metrics().clients());
    calls.put("POST /contacts", bs -> bs.contacts().create(sampleBody("POST /contacts")));
    calls.put("GET /contacts", bs -> bs.contacts().list(Map.of("limit", 2)));
    calls.put("POST /contacts/batch/get", bs -> bs.contacts().batch().get(sampleBody("POST /contacts/batch/get")));
    calls.put("POST /contacts/batch/remove", bs -> bs.contacts().batch().remove(sampleBody("POST /contacts/batch/remove")));
    calls.put("POST /contacts/batch", bs ->
            bs.contacts()
                .batch()
                .create(
                    sampleBody("POST /contacts/batch"),
                    Map.of("on_conflict", "skip"),
                    RequestOptions.builder().batchValidation(BatchValidation.PERMISSIVE).build()));
    calls.put("GET /contacts/{id}", bs -> bs.contacts().get("person@example.com"));
    calls.put("PATCH /contacts/{id}", bs -> bs.contacts().update(ID, sampleBody("PATCH /contacts/{id}")));
    calls.put("DELETE /contacts/{id}", bs -> bs.contacts().delete(ID, Map.of("erase", true)));
    calls.put("POST /contacts/{id}/preferences-link", bs -> bs.contacts().preferencesLink(ID));
    calls.put("GET /contacts/{id}/topics", bs -> bs.contacts().topics().list(ID));
    calls.put("PATCH /contacts/{id}/topics", bs -> bs.contacts().topics().update(ID, sampleBody("PATCH /contacts/{id}/topics")));
    calls.put("POST /contacts/{id}/segments/{segmentId}", bs -> bs.contacts().segments().add(ID, ID2));
    calls.put("DELETE /contacts/{id}/segments/{segmentId}", bs -> bs.contacts().segments().remove(ID, ID2));
    calls.put("POST /audiences/{audienceId}/contacts", bs -> bs.audiences().contacts().create(ID, sampleBody("POST /audiences/{audienceId}/contacts")));
    calls.put("GET /audiences/{audienceId}/contacts/{id}", bs -> bs.audiences().contacts().get(ID, ID2));
    calls.put("PATCH /audiences/{audienceId}/contacts/{id}", bs -> bs.audiences().contacts().update(ID, ID2, sampleBody("PATCH /audiences/{audienceId}/contacts/{id}")));
    calls.put("DELETE /audiences/{audienceId}/contacts/{id}", bs -> bs.audiences().contacts().delete(ID, ID2));
    calls.put("POST /contact-properties", bs -> bs.contactProperties().create(sampleBody("POST /contact-properties")));
    calls.put("GET /contact-properties", bs -> bs.contactProperties().list());
    calls.put("GET /contact-properties/{id}", bs -> bs.contactProperties().get(ID));
    calls.put("PATCH /contact-properties/{id}", bs -> bs.contactProperties().update(ID, sampleBody("PATCH /contact-properties/{id}")));
    calls.put("DELETE /contact-properties/{id}", bs -> bs.contactProperties().delete(ID));
    calls.put("POST /segments", bs -> bs.segments().create(sampleBody("POST /segments")));
    calls.put("GET /segments", bs -> bs.segments().list());
    calls.put("GET /segments/{id}", bs -> bs.segments().get(ID));
    calls.put("PATCH /segments/{id}", bs -> bs.segments().update(ID, sampleBody("PATCH /segments/{id}")));
    calls.put("DELETE /segments/{id}", bs -> bs.segments().delete(ID));
    calls.put("GET /segments/{id}/contacts", bs -> bs.segments().contacts().list(ID, Map.of("limit", 50)));
    calls.put("POST /broadcasts", bs -> bs.broadcasts().create(sampleBody("POST /broadcasts")));
    calls.put("GET /broadcasts", bs -> bs.broadcasts().list());
    calls.put("GET /broadcasts/{id}", bs -> bs.broadcasts().get(ID));
    calls.put("PATCH /broadcasts/{id}", bs -> bs.broadcasts().update(ID, sampleBody("PATCH /broadcasts/{id}")));
    calls.put("DELETE /broadcasts/{id}", bs -> bs.broadcasts().delete(ID));
    calls.put("POST /broadcasts/{id}/send", bs -> bs.broadcasts().send(ID, sampleBody("POST /broadcasts/{id}/send")));
    calls.put("POST /broadcasts/{id}/duplicate", bs -> bs.broadcasts().duplicate(ID));
    calls.put("POST /broadcasts/{id}/cancel", bs -> bs.broadcasts().cancel(ID));
    calls.put("POST /topics", bs -> bs.topics().create(sampleBody("POST /topics")));
    calls.put("GET /topics", bs -> bs.topics().list());
    calls.put("GET /topics/{id}", bs -> bs.topics().get(ID));
    calls.put("PATCH /topics/{id}", bs -> bs.topics().update(ID, sampleBody("PATCH /topics/{id}")));
    calls.put("DELETE /topics/{id}", bs -> bs.topics().delete(ID));
    calls.put("POST /domains", bs -> bs.domains().create(sampleBody("POST /domains")));
    calls.put("GET /domains", bs -> bs.domains().list());
    calls.put("GET /domains/{id}", bs -> bs.domains().get(ID));
    calls.put("PATCH /domains/{id}", bs -> bs.domains().update(ID, sampleBody("PATCH /domains/{id}")));
    calls.put("DELETE /domains/{id}", bs -> bs.domains().delete(ID));
    calls.put("POST /domains/{id}/verify", bs -> bs.domains().verify(ID));
    calls.put("POST /api-keys", bs -> bs.apiKeys().create(sampleBody("POST /api-keys")));
    calls.put("GET /api-keys", bs -> bs.apiKeys().list());
    calls.put("DELETE /api-keys/{id}", bs -> bs.apiKeys().delete(ID));
    calls.put("POST /webhooks", bs -> bs.webhooks().create(sampleBody("POST /webhooks")));
    calls.put("GET /webhooks", bs -> bs.webhooks().list());
    calls.put("GET /webhooks/{id}", bs -> bs.webhooks().get(ID));
    calls.put("PATCH /webhooks/{id}", bs -> bs.webhooks().update(ID, sampleBody("PATCH /webhooks/{id}")));
    calls.put("DELETE /webhooks/{id}", bs -> bs.webhooks().delete(ID));
    calls.put("POST /webhooks/{id}/rotate", bs -> bs.webhooks().rotate(ID, sampleBody("POST /webhooks/{id}/rotate")));
    calls.put("POST /suppressions/batch/add", bs -> bs.suppressions().batch().add(sampleBody("POST /suppressions/batch/add")));
    calls.put("POST /suppressions/batch/remove", bs -> bs.suppressions().batch().remove(sampleBody("POST /suppressions/batch/remove")));
    calls.put("POST /suppressions", bs -> bs.suppressions().create(sampleBody("POST /suppressions")));
    calls.put("GET /suppressions", bs -> bs.suppressions().list(Map.of("origin", "manual")));
    calls.put("GET /suppressions/{id}", bs -> bs.suppressions().get(ID));
    calls.put("DELETE /suppressions/{id}", bs -> bs.suppressions().delete(ID));
    calls.put("GET /unsubscribes", bs -> bs.unsubscribes().list());
    calls.put("POST /unsubscribes", bs -> bs.unsubscribes().create(sampleBody("POST /unsubscribes")));
    calls.put("DELETE /unsubscribes/{id}", bs -> bs.unsubscribes().delete(ID));
    calls.put("POST /templates", bs -> bs.templates().create(sampleBody("POST /templates")));
    calls.put("GET /templates", bs -> bs.templates().list());
    calls.put("GET /templates/{id}", bs -> bs.templates().get(ID));
    calls.put("PATCH /templates/{id}", bs -> bs.templates().update(ID, sampleBody("PATCH /templates/{id}")));
    calls.put("DELETE /templates/{id}", bs -> bs.templates().delete(ID));
    calls.put("POST /templates/{id}/publish", bs -> bs.templates().publish(ID));
    calls.put("POST /templates/{id}/duplicate", bs -> bs.templates().duplicate(ID));
    calls.put("GET /verifications/availability", bs -> bs.verifications().availability());
    calls.put("POST /verifications/estimate", bs -> bs.verifications().estimate(sampleBody("POST /verifications/estimate")));
    calls.put("POST /verifications", bs -> bs.verifications().create(sampleBody("POST /verifications")));
    calls.put("GET /verifications", bs -> bs.verifications().list());
    calls.put("GET /verifications/{id}", bs -> bs.verifications().get(ID));
    calls.put("GET /usage", bs -> bs.usage().get());
    calls.put("GET /inboxes/events", bs ->
            bs.inboxes()
                .events()
                .stream(
                    Map.of("types", "inbox.email.received"),
                    EventStreamOptions.builder().reconnect(false).build()));
    calls.put("GET /inboxes/{inbox_id}/events", bs ->
            bs.inboxes()
                .events()
                .streamInbox(INBOX, null, EventStreamOptions.builder().reconnect(false).build()));
    calls.put("POST /inboxes", bs -> bs.inboxes().create(sampleBody("POST /inboxes")));
    calls.put("GET /inboxes", bs -> bs.inboxes().list(Map.of("kind", "agent")));
    calls.put("GET /inboxes/{inbox_id}", bs -> bs.inboxes().get(INBOX));
    calls.put("PATCH /inboxes/{inbox_id}", bs -> bs.inboxes().update(INBOX, sampleBody("PATCH /inboxes/{inbox_id}")));
    calls.put("DELETE /inboxes/{inbox_id}", bs -> bs.inboxes().delete(INBOX));
    calls.put("GET /inboxes/{inbox_id}/agent", bs -> bs.inboxes().agent().get(INBOX));
    calls.put("GET /inboxes/{inbox_id}/safety", bs -> bs.inboxes().safety().get(INBOX));
    calls.put("PATCH /inboxes/{inbox_id}/safety", bs -> bs.inboxes().safety().update(INBOX, sampleBody("PATCH /inboxes/{inbox_id}/safety")));
    calls.put("GET /inboxes/{inbox_id}/labels", bs -> bs.inboxes().labels().list(INBOX));
    calls.put("POST /inboxes/{inbox_id}/labels", bs -> bs.inboxes().labels().create(INBOX, sampleBody("POST /inboxes/{inbox_id}/labels")));
    calls.put("PATCH /inboxes/{inbox_id}/labels/{label_id}", bs -> bs.inboxes().labels().update(INBOX, ID2, sampleBody("PATCH /inboxes/{inbox_id}/labels/{label_id}")));
    calls.put("DELETE /inboxes/{inbox_id}/labels/{label_id}", bs -> bs.inboxes().labels().delete(INBOX, ID2));
    calls.put("GET /inboxes/{inbox_id}/threads/search", bs ->
            bs.inboxes()
                .threads()
                .search(INBOX, Map.of("query", "invoice", "read", false, "folders", "inbox,archive")));
    calls.put("GET /inboxes/{inbox_id}/threads", bs -> bs.inboxes().threads().list(INBOX));
    calls.put("GET /inboxes/{inbox_id}/threads/{thread_id}", bs -> bs.inboxes().threads().get(ID, ID2));
    calls.put("PATCH /inboxes/{inbox_id}/threads/{thread_id}", bs -> bs.inboxes().threads().update(ID, ID2, sampleBody("PATCH /inboxes/{inbox_id}/threads/{thread_id}")));
    calls.put("DELETE /inboxes/{inbox_id}/threads/{thread_id}", bs -> bs.inboxes().threads().delete(ID, ID2));
    calls.put("POST /inboxes/{inbox_id}/threads/{thread_id}/labels", bs -> bs.inboxes().threads().labels().add(ID, ID2, sampleBody("POST /inboxes/{inbox_id}/threads/{thread_id}/labels")));
    calls.put("PUT /inboxes/{inbox_id}/threads/{thread_id}/labels", bs -> bs.inboxes().threads().labels().set(ID, ID2, sampleBody("PUT /inboxes/{inbox_id}/threads/{thread_id}/labels")));
    calls.put("DELETE /inboxes/{inbox_id}/threads/{thread_id}/labels/{label_id}", bs -> bs.inboxes().threads().labels().remove(ID, ID2, ID3));
    calls.put("GET /inboxes/{inbox_id}/threads/{thread_id}/emails", bs -> bs.inboxes().threads().emails().list(ID, ID2));
    calls.put("GET /inboxes/{inbox_id}/threads/{thread_id}/emails/{email_id}", bs -> bs.inboxes().threads().emails().get(ID, ID2, ID3));
    calls.put("GET /inboxes/{inbox_id}/quarantine", bs -> bs.inboxes().quarantine().list(INBOX));
    calls.put("POST /inboxes/{inbox_id}/quarantine/{email_id}/release", bs -> bs.inboxes().quarantine().release(INBOX, ID2, sampleBody("POST /inboxes/{inbox_id}/quarantine/{email_id}/release")));
    calls.put("GET /inboxes/{inbox_id}/codes/latest", bs -> bs.inboxes().codes().latest(INBOX, Map.of("from", "example.com", "wait", 30)));
    calls.put("POST /inboxes/{inbox_id}/threads/{thread_id}/emails/{email_id}/reply", bs -> bs.inboxes().threads().emails().reply(ID, ID2, ID3, sampleBody("POST /inboxes/{inbox_id}/threads/{thread_id}/emails/{email_id}/reply")));
    calls.put("POST /inboxes/{inbox_id}/threads/{thread_id}/emails/{email_id}/forward", bs -> bs.inboxes().threads().emails().forward(ID, ID2, ID3, sampleBody("POST /inboxes/{inbox_id}/threads/{thread_id}/emails/{email_id}/forward")));
    calls.put("POST /inboxes/{inbox_id}/emails", bs -> bs.inboxes().send(INBOX, sampleBody("POST /inboxes/{inbox_id}/emails")));
    calls.put("POST /inboxes/{inbox_id}/drafts", bs -> bs.inboxes().drafts().create(INBOX, sampleBody("POST /inboxes/{inbox_id}/drafts")));
    calls.put("GET /inboxes/{inbox_id}/drafts", bs -> bs.inboxes().drafts().list(INBOX, Map.of("limit", 20)));
    calls.put("GET /inboxes/{inbox_id}/drafts/{draft_id}", bs -> bs.inboxes().drafts().get(INBOX, ID2));
    calls.put("PATCH /inboxes/{inbox_id}/drafts/{draft_id}", bs -> bs.inboxes().drafts().update(INBOX, ID2, sampleBody("PATCH /inboxes/{inbox_id}/drafts/{draft_id}")));
    calls.put("DELETE /inboxes/{inbox_id}/drafts/{draft_id}", bs -> bs.inboxes().drafts().delete(INBOX, ID2));
    calls.put("POST /inboxes/{inbox_id}/drafts/{draft_id}/send", bs ->
            bs.inboxes()
                .drafts()
                .send(INBOX, ID2, RequestOptions.builder().idempotencyKey("d-1").build()));
    calls.put("POST /inboxes/{inbox_id}/drafts/{draft_id}/reject", bs -> bs.inboxes().drafts().reject(INBOX, ID2));
    calls.put("POST /emails/receiving/{id}/reply", bs ->
            bs.emails()
                .receiving()
                .reply(
                    ID,
                    sampleBody("POST /emails/receiving/{id}/reply"),
                    RequestOptions.builder().idempotencyKey("r-1").build()));
    calls.put("POST /emails/receiving/{id}/forward", bs -> bs.emails().receiving().forward(ID, sampleBody("POST /emails/receiving/{id}/forward")));
    calls.put("POST /agent/signup", bs -> bs.agent().signup(sampleBody("POST /agent/signup")));
    calls.put("GET /agent/status", bs -> bs.agent().status());
    calls.put("POST /agent/claim-email", bs -> bs.agent().claimEmail());
    calls.put("POST /emails", bs -> bs.emails().send(SEND, RequestOptions.builder().idempotencyKey("welcome-1").build()));
    calls.put("GET /emails", bs -> bs.emails().list(Map.of("limit", 10)));
    calls.put("POST /emails/batch", bs -> bs.emails().batch(List.of(SEND, SEND)));
    calls.put("POST /emails/{id}/cancel", bs -> bs.emails().cancel(ID));
    calls.put("PATCH /emails/{id}", bs -> bs.emails().update(ID, sampleBody("PATCH /emails/{id}")));
    calls.put("GET /emails/{id}", bs -> bs.emails().get(ID));
    calls.put("DELETE /emails/{id}", bs -> bs.emails().delete(ID));
    calls.put("GET /emails/receiving", bs -> bs.emails().receiving().list(Map.of("limit", 5, "unread", "true")));
    calls.put("GET /emails/receiving/{id}", bs -> bs.emails().receiving().get(ID, Map.of("html_format", "cid")));
    calls.put("DELETE /emails/receiving/{id}", bs -> bs.emails().receiving().delete(ID));
    calls.put("GET /emails/receiving/{email_id}/attachments", bs -> bs.emails().receiving().attachments().list(ID));
    calls.put("GET /emails/receiving/{email_id}/attachments/{id}", bs -> bs.emails().receiving().attachments().get(ID, ID2));
    calls.put("GET /emails/receiving/{email_id}/attachments/{id}/text", bs -> bs.emails().receiving().attachments().text(ID, ID2));
    calls.put("GET /emails/{id}/insights", bs -> bs.emails().insights(ID));
    calls.put("GET /deliverability", bs -> bs.deliverability().get());
    return calls;
  }

  @Test
  void hasExactlyOneCallPerOperationInTheSnapshot() {
    Set<String> table = new TreeSet<>(CALLS.keySet());
    Set<String> spec = new TreeSet<>(ContractSpec.OPERATIONS.keySet());
    assertEquals(139, spec.size(), "operations in the snapshot");
    assertEquals(
        List.of(),
        spec.stream().filter(k -> !table.contains(k)).collect(Collectors.toList()),
        "operations without an SDK call");
    assertEquals(
        List.of(),
        table.stream().filter(k -> !spec.contains(k)).collect(Collectors.toList()),
        "SDK calls for unknown operations");
  }

  /** The resource object an operationId's group names: emails.receiving -> client.emails().receiving(). */
  static Object owner(BillionSend client, List<String> group) throws Exception {
    Object at = client;
    for (String name : group) {
      at = at.getClass().getMethod(name).invoke(at);
    }
    return at;
  }

  /** Whether {@code owner.name()} is the accessor of a resource group. */
  static boolean isGroup(Object owner, String name) {
    try {
      return owner.getClass().getMethod(name).getReturnType().getSimpleName().endsWith("Resource");
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  static boolean hasMethod(Object owner, String name, Class<?> returns) {
    for (Method m : owner.getClass().getMethods()) {
      if (m.getName().equals(name)
          && Modifier.isPublic(m.getModifiers())
          && returns.isAssignableFrom(m.getReturnType())) {
        return true;
      }
    }
    return false;
  }

  @Test
  void namesEachMethodAfterItsOperationId() throws Exception {
    BillionSend client = client();
    List<String> missing = new ArrayList<>();
    for (ContractSpec.Operation op : ContractSpec.OPERATIONS.values()) {
      List<String> path = new ArrayList<>(List.of(op.operationId().split("\\.")));
      String method = path.remove(path.size() - 1);
      Object owner = owner(client, path);
      if (isGroup(owner, method)) {
        // emails.metrics is also the group of emails.metrics.links: it is metrics().get().
        owner = owner.getClass().getMethod(method).invoke(owner);
        method = "get";
      }
      Class<?> returns = op.isStream() ? EventStream.class : JsonNode.class;
      if (!hasMethod(owner, method, returns)) {
        missing.add(op.operationId());
      }
    }
    assertEquals(List.of(), missing, "operations whose operationId is not an SDK method");
  }

  @Test
  void givesEveryCursorListAnIteratorBesideItsMethod() throws Exception {
    BillionSend client = client();
    List<String> lists = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (ContractSpec.Operation op : ContractSpec.OPERATIONS.values()) {
      if (!op.cursorList()) {
        continue;
      }
      lists.add(op.operationId());
      List<String> path = new ArrayList<>(List.of(op.operationId().split("\\.")));
      String method = path.remove(path.size() - 1);
      Object owner = owner(client, path);
      if (isGroup(owner, method)) {
        owner = owner.getClass().getMethod(method).invoke(owner);
        method = "get";
      }
      if (!hasMethod(owner, method + "All", CursorIterable.class)) {
        missing.add(op.operationId());
      }
    }
    assertEquals(21, lists.size(), "cursor lists in the snapshot");
    assertEquals(List.of(), missing, "cursor lists without a listAll / searchAll");
  }

  @TestFactory
  Stream<DynamicTest> reachesEachOperationWithFullAnswers() {
    return CALLS.entrySet().stream()
        .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> reach(entry.getKey(), entry.getValue(), false)));
  }

  @TestFactory
  Stream<DynamicTest> reachesEachOperationWithMinimalAnswers() {
    return CALLS.entrySet().stream()
        .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> reach(entry.getKey(), entry.getValue(), true)));
  }

  /**
   * Calls one operation against the contract mock, whose generated answer carries every property
   * or only the required ones, and checks the request, the operation reached and the answer.
   */
  private void reach(String key, Function<BillionSend, Object> call, boolean minimal) throws Exception {
    mock.minimal(minimal);
    try {
      Object result = call.apply(client());
      ContractSpec.Operation op = ContractSpec.operation(key);
      if (result instanceof EventStream) {
        List<String> events = new ArrayList<>();
        try (EventStream stream = (EventStream) result) {
          for (InboxStreamItem item : stream) {
            events.add(item.event());
          }
        }
        assertEquals(List.of("inbox.email.received"), events);
      } else {
        JsonNode answer = (JsonNode) result;
        ContractMock.Hit hit = mock.hits.get(0);
        assertNotNull(hit.response, "the mock answered");
        assertEquals(ContractSpec.MAPPER.readTree(hit.response.toString()), answer, "returns the answer");
        String status = op.successStatus();
        assertEquals(List.of(), ContractSpec.validate(op.responsePointer(status), answer));
      }
      assertEquals(List.of(), mock.violations, "contract violations");
      assertEquals(List.of(key), mock.hits.stream().map(h -> h.key).collect(Collectors.toList()));
      synchronized (REACHED) {
        REACHED.get(minimal).add(key);
      }
    } finally {
      mock.minimal(false);
      checkContract();
    }
  }

  @AfterAll
  static void reachedEveryOperationAndNothingElse() {
    Set<String> spec = ContractSpec.OPERATIONS.keySet();
    for (boolean minimal : List.of(false, true)) {
      Set<String> reached = REACHED.get(minimal);
      if (reached.isEmpty()) {
        continue; // a filtered run that called no operation
      }
      String answers = minimal ? "minimal answers" : "full answers";
      assertEquals(
          List.of(),
          spec.stream().filter(k -> !reached.contains(k)).collect(Collectors.toList()),
          "operations no call reached with " + answers);
      assertTrue(spec.containsAll(reached), "reached operations outside the snapshot");
      assertEquals(spec, reached, "operations reached with " + answers);
    }
  }
}
