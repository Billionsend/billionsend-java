package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.billionsend.contract.ContractMock;
import com.billionsend.contract.ContractSpec;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

/** Starts one contract mock per test class and checks after each test that nothing broke the contract. */
abstract class MockTestBase {
  static final String ID = ContractSpec.SAMPLE_UUID;
  static final String ID2 = "0d5b0a52-8c1f-4a3e-b0f4-7d8e9a1b2c3d";
  static final String API_KEY = "bs_test_0123456789";

  static ContractMock mock;

  /** The waits the client asked for, in milliseconds (the tests do not really wait). */
  final List<Long> sleeps = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void startMock() throws Exception {
    mock = new ContractMock();
    mock.start();
  }

  @AfterAll
  static void stopMock() {
    mock.stop();
  }

  @AfterEach
  void checkContract() {
    List<String> violations = new ArrayList<>(mock.violations);
    mock.reset();
    sleeps.clear();
    assertEquals(List.of(), violations, "contract violations");
  }

  BillionSend.Builder builder() {
    return BillionSend.builder()
        .apiKey(API_KEY)
        .baseUrl(mock.url())
        .env(name -> null)
        .sleeper(sleeps::add);
  }

  BillionSend client() {
    return builder().build();
  }

  /** A request body with every property, valid against the operation's request schema. */
  static JsonNode sampleBody(String key) {
    return ContractSpec.example(ContractSpec.operation(key).requestSchema(), true);
  }
}
