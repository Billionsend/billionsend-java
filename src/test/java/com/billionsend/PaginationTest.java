package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.billionsend.contract.ContractMock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaginationTest extends MockTestBase {
  static List<String> ids(Iterable<JsonNode> items) {
    List<String> out = new ArrayList<>();
    items.forEach(item -> out.add(item.get("id").asText()));
    return out;
  }

  @Test
  void walksForwardWithAfterTheLastIdUntilHasMoreIsFalse() {
    mock.reply("GET /contacts", 200, ContractMock.page(List.of("c1", "c2"), true));
    mock.reply("GET /contacts", 200, ContractMock.page(List.of("c3"), false));
    List<String> seen = ids(client().contacts().listAll(Map.of("limit", 2)));
    assertEquals(List.of("c1", "c2", "c3"), seen);
    List<ContractMock.Hit> hits = mock.hitsFor("GET /contacts");
    assertEquals(2, hits.size());
    assertNull(hits.get(0).query("after"));
    assertEquals("2", hits.get(0).query("limit"));
    assertEquals("c2", hits.get(1).query("after"));
    assertEquals("2", hits.get(1).query("limit"));
  }

  @Test
  void walksBackwardWithBeforeTheFirstIdWhenStartedWithBefore() {
    mock.reply("GET /emails", 200, ContractMock.page(List.of("e5", "e6"), true));
    mock.reply("GET /emails", 200, ContractMock.page(List.of("e7"), false));
    List<String> seen = ids(client().emails().listAll(Map.of("before", "e4")));
    assertEquals(List.of("e5", "e6", "e7"), seen);
    List<ContractMock.Hit> hits = mock.hitsFor("GET /emails");
    assertEquals("e4", hits.get(0).query("before"));
    assertEquals("e5", hits.get(1).query("before"));
    assertNull(hits.get(1).query("after"));
  }

  @Test
  void stopsEarlyWhenTheCallerBreaksOut() {
    mock.reply("GET /domains", 200, ContractMock.page(List.of("d1", "d2"), true));
    String first = null;
    for (JsonNode domain : client().domains().listAll()) {
      first = domain.get("id").asText();
      break;
    }
    assertEquals("d1", first);
    assertEquals(1, mock.hitsFor("GET /domains").size());
  }

  @Test
  void followsNestedListsWithTheirPathParameters() {
    mock.reply("GET /inboxes/{inbox_id}/threads/{thread_id}/emails", 200, ContractMock.page(List.of("m1"), true));
    mock.reply("GET /inboxes/{inbox_id}/threads/{thread_id}/emails", 200, ContractMock.page(List.of("m2"), false));
    List<String> seen =
        client().inboxes().threads().emails().listAll("support@example.com", ID, Map.of("include_quarantined", true)).stream()
            .map(item -> item.get("id").asText())
            .collect(Collectors.toList());
    assertEquals(List.of("m1", "m2"), seen);
    List<ContractMock.Hit> hits = mock.hitsFor("GET /inboxes/{inbox_id}/threads/{thread_id}/emails");
    assertEquals("/inboxes/support%40example.com/threads/" + ID + "/emails", hits.get(1).rawPath);
    assertEquals("m1", hits.get(1).query("after"));
    assertEquals("true", hits.get(1).query("include_quarantined"));
  }

  @Test
  void throwsThePageErrorAsItsErrorClass() {
    mock.reply("GET /segments", 200, ContractMock.page(List.of("s1"), true));
    mock.reply("GET /segments", 403, ContractMock.error(403, "restricted_api_key", "No"));
    List<String> seen = new ArrayList<>();
    PermissionDeniedException error =
        assertThrows(
            PermissionDeniedException.class,
            () -> {
              for (JsonNode segment : client().segments().listAll()) {
                seen.add(segment.get("id").asText());
              }
            });
    assertEquals(List.of("s1"), seen);
    assertEquals("restricted_api_key", error.getName());
  }

  @Test
  void startsAgainOnEachIteration() {
    mock.reply("GET /contact-properties", 200, ContractMock.page(List.of("p1"), false));
    mock.reply("GET /contact-properties", 200, ContractMock.page(List.of("p1"), false));
    CursorIterable properties = client().contactProperties().listAll();
    assertEquals(List.of("p1"), ids(properties));
    assertEquals(List.of("p1"), ids(properties));
    assertEquals(2, mock.hitsFor("GET /contact-properties").size());
  }

  @Test
  void worksWithAnyPageFetcher() {
    Map<String, List<String>> pages = new HashMap<>();
    pages.put("", List.of("a", "b"));
    pages.put("b", List.of("c"));
    List<Map<String, Object>> queries = new ArrayList<>();
    CursorIterable items =
        new CursorIterable(
            query -> {
              queries.add(query);
              String after = (String) query.getOrDefault("after", "");
              List<String> page = pages.get(after);
              return ContractMock.page(page, !after.equals("b"));
            },
            Map.of("limit", 2));
    assertEquals(List.of("a", "b", "c"), ids(items));
    assertEquals(List.of(Map.of("limit", 2), Map.of("limit", 2, "after", "b")), queries);
  }

  @Test
  void stopsOnAnEmptyPageEvenWhenHasMoreSaysOtherwise() {
    mock.reply("GET /webhooks", 200, ContractMock.page(List.of(), true));
    assertEquals(List.of(), ids(client().webhooks().listAll()));
    assertEquals(1, mock.hitsFor("GET /webhooks").size());
  }
  @Test
  void throwsOnAPageWithoutADataArray() {
    List<Object> answers = List.of(Map.of("object", "list"), Map.of("data", Map.of()), List.of());
    for (Object answer : answers) {
      mock.reply("GET /domains", 200, answer);
      BillionSendException error =
          assertThrows(BillionSendException.class, () -> ids(client().domains().listAll()), answer.toString());
      assertEquals(BillionSendException.class, error.getClass());
      assertEquals("application_error", error.getName());
      assertNull(error.getStatusCode());
      assertEquals("The list page has no data array", error.getMessage());
    }
    assertEquals(3, mock.hitsFor("GET /domains").size());
  }

  @Test
  void handsOutThePageThenThrowsWhenTheItemToContinueFromHasNoId() {
    ObjectNode page = ContractMock.page(List.of("a"), true);
    ((ArrayNode) page.get("data")).addObject().put("name", "no id");
    mock.reply("GET /domains", 200, page);
    List<JsonNode> seen = new ArrayList<>();
    BillionSendException error =
        assertThrows(
            BillionSendException.class,
            () -> {
              for (JsonNode domain : client().domains().listAll()) {
                seen.add(domain);
              }
            });
    assertEquals(2, seen.size());
    assertEquals("a", seen.get(0).get("id").asText());
    assertEquals("application_error", error.getName());
    assertNull(error.getStatusCode());
    assertEquals("The last item of the list page has no id to continue from", error.getMessage());
    assertEquals(1, mock.hitsFor("GET /domains").size());

    // Walking backward continues from the first item.
    ObjectNode backward = ContractMock.page(List.of("z"), true);
    ((ArrayNode) backward.get("data")).insertObject(0).put("name", "no id");
    mock.reply("GET /domains", 200, backward);
    List<JsonNode> back = new ArrayList<>();
    BillionSendException first =
        assertThrows(
            BillionSendException.class,
            () -> client().domains().listAll(Map.of("before", "y")).forEach(back::add));
    assertEquals(2, back.size());
    assertEquals("The first item of the list page has no id to continue from", first.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"id\":\"\"}", "{\"id\":null}", "{\"id\":7}", "{\"id\":{}}", "{\"id\":[]}"})
  void throwsWhenTheIdToContinueFromIsNotANonEmptyString(String item) throws Exception {
    ObjectNode page = ContractMock.page(List.of("a"), true);
    ((ArrayNode) page.get("data")).add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(item));
    mock.reply("GET /domains", 200, page);
    List<JsonNode> seen = new ArrayList<>();
    BillionSendException error =
        assertThrows(
            BillionSendException.class,
            () -> client().domains().listAll().forEach(seen::add));
    assertEquals(2, seen.size());
    assertEquals("application_error", error.getName());
    assertEquals(1, mock.hitsFor("GET /domains").size());
  }
}
