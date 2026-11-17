package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.billionsend.contract.ContractSpec;
import com.billionsend.internal.Call;
import com.billionsend.internal.Transport;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The contract mock itself catches what it is there to catch. */
class ContractMockTest extends MockTestBase {
  private List<String> takeViolations() {
    List<String> out = List.copyOf(mock.violations);
    mock.violations.clear();
    return out;
  }

  @Test
  void prefersLiteralPathSegmentsOverParameters() {
    assertEquals("GET /emails/metrics", ContractSpec.match("GET", "/emails/metrics").key);
    assertEquals("GET /emails/{id}", ContractSpec.match("GET", "/emails/abc").key);
    assertEquals("POST /contacts/batch", ContractSpec.match("POST", "/contacts/batch").key);
    assertEquals("GET /inboxes/events", ContractSpec.match("GET", "/inboxes/events").key);
  }

  @Test
  void validatesBodiesAgainstTheSchema() {
    ContractSpec.Operation send = ContractSpec.operation("POST /emails");
    assertTrue(ContractSpec.validate(send.requestPointer(), sampleBody("POST /emails")).isEmpty());
    assertFalse(ContractSpec.validate(send.requestPointer(), ContractSpec.MAPPER.createObjectNode()).isEmpty());
    assertFalse(
        ContractSpec.validate(send.requestPointer(), ContractSpec.MAPPER.valueToTree(Map.of("to", 5))).isEmpty());
  }

  @Test
  void flagsAQueryValueOutsideItsSchema() {
    client().emails().receiving().get(ID, Map.of("html_format", "bogus"));
    List<String> violations = takeViolations();
    assertEquals(1, violations.size(), violations.toString());
    assertTrue(violations.get(0).contains("query html_format=bogus"), violations.toString());
  }

  @Test
  void flagsAnUndeclaredQueryParameterAndABodyOutsideItsSchema() {
    client().emails().list(Map.of("color", "blue"));
    client().domains().create(Map.of("unexpected", true));
    List<String> violations = takeViolations();
    assertEquals(2, violations.size(), violations.toString());
    assertTrue(violations.get(0).contains("undeclared query parameter color"));
    assertTrue(violations.get(1).contains("body does not validate"));
  }

  @Test
  void flagsARequestOutsideTheSnapshot() {
    // No SDK method reaches a path outside the snapshot, so go through the transport directly.
    Transport transport =
        new Transport(API_KEY, mock.url(), Duration.ofSeconds(5), 0, Map.of(), HttpClient.newHttpClient(), ms -> {});
    assertThrows(NotFoundException.class, () -> transport.execute(Call.of("GET", "/nowhere"), null));
    assertEquals(List.of("no operation for GET /nowhere"), takeViolations());
  }
}
