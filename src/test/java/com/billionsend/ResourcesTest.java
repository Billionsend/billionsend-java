package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.billionsend.contract.ContractMock;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ResourcesTest extends MockTestBase {
  static final Map<String, Object> SEND =
      Map.of(
          "from", "Acme <hello@example.com>",
          "to", List.of("person@example.com"),
          "subject", "Hi",
          "html", "<p>Hi</p>");

  @Test
  void sendsABatchWithTheBatchValidationHeader() {
    client()
        .emails()
        .batch(List.of(SEND, SEND), RequestOptions.builder().batchValidation(BatchValidation.PERMISSIVE).build());
    ContractMock.Hit hit = mock.hitsFor("POST /emails/batch").get(0);
    assertEquals("permissive", hit.header("x-batch-validation"));
    assertEquals(2, hit.json().size());
    assertTrue(hit.header("idempotency-key") != null);
  }

  @Test
  void sendsTheBatchValidationHeaderOnlyToTheOperationsThatTakeIt() {
    client()
        .domains()
        .create(Map.of("name", "example.com"), RequestOptions.builder().batchValidation(BatchValidation.STRICT).build());
    assertNull(mock.hits.get(0).header("x-batch-validation"));
  }

  @Test
  void importsContactsWithOnConflictAndTheValidationHeader() {
    JsonNode body = sampleBody("POST /contacts/batch");
    client()
        .contacts()
        .batch()
        .create(body, Map.of("on_conflict", "upsert"), RequestOptions.builder().batchValidation(BatchValidation.STRICT).build());
    ContractMock.Hit hit = mock.hits.get(0);
    assertEquals("upsert", hit.query("on_conflict"));
    assertEquals("strict", hit.header("x-batch-validation"));
    assertEquals(body, hit.json());
  }

  @Test
  void sendsListQueryParametersCommaSeparatedInOneKeyAndDropsNulls() {
    Map<String, Object> query = new HashMap<>();
    query.put("metrics", List.of("delivered", "opened"));
    query.put("dimensions", new String[] {"period", "domain"});
    query.put("granularity", "daily");
    query.put("timezone", null);
    client().emails().metrics().get(query);
    ContractMock.Hit hit = mock.hits.get(0);
    assertEquals("delivered,opened", hit.query("metrics"));
    assertEquals("period,domain", hit.query("dimensions"));
    assertEquals(List.of("delivered,opened"), hit.query.get("metrics"));
    assertTrue(!hit.query.containsKey("timezone"));
  }

  @Test
  void writesBooleansAndNumbersAsText() {
    client().inboxes().threads().search("support@example.com", Map.of("read", false, "limit", 5));
    ContractMock.Hit hit = mock.hits.get(0);
    assertEquals("false", hit.query("read"));
    assertEquals("5", hit.query("limit"));
    assertEquals("/inboxes/support%40example.com/threads/search", hit.rawPath);
  }

  @Test
  void leavesOutAnOptionalBodyWhenNoneIsGiven() {
    client().inboxes().drafts().send("support@example.com", ID);
    client().broadcasts().send(ID, Map.of("scheduled_at", "in 1 hour"));
    List<ContractMock.Hit> hits = mock.hits;
    assertEquals("", hits.get(0).body);
    assertNull(hits.get(0).header("content-type"));
    assertTrue(hits.get(0).header("idempotency-key") != null);
    assertEquals("in 1 hour", hits.get(1).json().get("scheduled_at").asText());
  }

  @Test
  void reachesTheOperationThatIsAlsoAGroupAsItsGet() {
    client().emails().metrics().get(Map.of("granularity", "weekly"));
    client().emails().metrics().clients(Map.of("start_date", "2026-11-01"));
    assertEquals("GET /emails/metrics", mock.hits.get(0).key);
    assertEquals("GET /emails/metrics/clients", mock.hits.get(1).key);
  }

  @Test
  void theVersionMatchesTheProjectVersion() throws Exception {
    String pom = Files.readString(Path.of("pom.xml"));
    Matcher matcher = Pattern.compile("<artifactId>billionsend-java</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
    assertTrue(matcher.find());
    assertEquals(matcher.group(1), BillionSend.VERSION);
    String changelog = Files.readString(Path.of("CHANGELOG.md"));
    assertTrue(changelog.contains("## " + BillionSend.VERSION), "the changelog has a section for this version");
  }
}
