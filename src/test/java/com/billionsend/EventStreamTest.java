package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.billionsend.contract.ContractMock;
import com.billionsend.internal.ServerSentEvent;
import com.billionsend.internal.SseParser;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventStreamTest extends MockTestBase {
  static List<ServerSentEvent> parse(List<Integer> retries, byte[]... chunks) {
    SseParser parser = new SseParser(retries::add, null);
    List<ServerSentEvent> events = new ArrayList<>();
    for (byte[] chunk : chunks) {
      events.addAll(parser.feed(chunk, 0, chunk.length));
    }
    events.addAll(parser.finish());
    return events;
  }

  static List<ServerSentEvent> parse(String... chunks) {
    return parse(
        new ArrayList<>(),
        Arrays.stream(chunks).map(c -> c.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new));
  }

  static JsonNode json(String text) throws Exception {
    return com.billionsend.contract.ContractSpec.MAPPER.readTree(text);
  }

  @Nested
  class Parser {
    @Test
    void parsesIdEventAndJsonData() throws Exception {
      List<ServerSentEvent> events =
          parse("id: 7\nevent: inbox.email.received\ndata: {\"type\":\"inbox.email.received\",\"inbox_id\":\"x\"}\n\n");
      assertEquals(
          List.of(
              new ServerSentEvent(
                  "7",
                  "inbox.email.received",
                  json("{\"type\":\"inbox.email.received\",\"inbox_id\":\"x\"}"),
                  "{\"type\":\"inbox.email.received\",\"inbox_id\":\"x\"}")),
          events);
    }

    @Test
    void joinsEventsSplitAcrossChunksIncludingACrlfSplitInTwo() throws Exception {
      List<ServerSentEvent> events =
          parse("id: 1\r", "\nda", "ta: {\"a\"", ":1}\r\n\r", "\nid: 2\r\ndata: 2\r\n\r\n");
      assertEquals(List.of("1", "2"), events.stream().map(ServerSentEvent::id).collect(Collectors.toList()));
      assertEquals(json("{\"a\":1}"), events.get(0).data());
      assertEquals(json("2"), events.get(1).data());
    }

    @Test
    void splitsAMultiByteCharacterAcrossChunksCorrectly() {
      byte[] bytes = "data: héllo\n\n".getBytes(StandardCharsets.UTF_8);
      List<ServerSentEvent> events =
          parse(new ArrayList<>(), Arrays.copyOfRange(bytes, 0, 8), Arrays.copyOfRange(bytes, 8, bytes.length));
      assertEquals("héllo", events.get(0).data().asText());
    }

    @Test
    void ignoresCommentsUnknownFieldsAndEventsWithoutData() {
      List<ServerSentEvent> events = parse(": keep-alive\n\nfoo: bar\nevent: ping\n\n:another\ndata: x\n\n");
      assertEquals(1, events.size());
      assertNull(events.get(0).id());
      assertEquals("message", events.get(0).event());
      assertEquals("x", events.get(0).raw());
      assertTrue(events.get(0).data().isTextual());
    }

    @Test
    void joinsMultiLineDataWithNewlinesAndKeepsNonJsonAsText() {
      List<ServerSentEvent> events = parse("data: first\ndata:second\ndata\n\n");
      assertEquals("first\nsecond\n", events.get(0).raw());
      assertEquals("first\nsecond\n", events.get(0).data().asText());
    }

    @Test
    void keepsTheLastIdForLaterEventsAndReportsRetry() {
      List<Integer> retries = new ArrayList<>();
      List<ServerSentEvent> events =
          parse(
              retries,
              "﻿id: 5\ndata: a\n\nretry: 2500\ndata: b\n\nretry: soon\nid\ndata: c\n\n"
                  .getBytes(StandardCharsets.UTF_8));
      assertEquals(List.of("5", "5", ""), events.stream().map(ServerSentEvent::id).collect(Collectors.toList()));
      assertEquals(List.of(2500), retries);
    }

    @Test
    void dropsAnEventTheStreamCutOffBeforeItsBlankLine() {
      List<ServerSentEvent> events = parse("data: complete\n\ndata: partial\n");
      assertEquals(List.of("complete"), events.stream().map(ServerSentEvent::raw).collect(Collectors.toList()));
    }
  }

  @Nested
  class Streams {
    @Test
    void streamsTheTeamsEventsAndClosesWhenTheLoopEnds() throws Exception {
      CountDownLatch closed =
          mock.stream(
              "GET /inboxes/events",
              w -> {
                w.write("id: 10\nevent: inbox.thread.created\ndata: {\"type\":\"inbox.thread.created\"}\n\n");
                w.write(": keep-alive\n\n");
                w.write("id: 11\nevent: email.received\ndata: {\"type\":\"email.received\"}\n\n");
                // stays open
              });
      List<String> names = new ArrayList<>();
      EventStream stream =
          client().inboxes().events().stream(Map.of("types", List.of("inbox.thread.created", "email.received")));
      try (stream) {
        for (InboxStreamItem item : stream) {
          names.add(item.event());
          if (names.size() == 2) {
            break;
          }
        }
      }
      assertEquals(List.of("inbox.thread.created", "email.received"), names);
      assertEquals("11", stream.lastEventId());
      ContractMock.Hit hit = mock.hitsFor("GET /inboxes/events").get(0);
      assertEquals("inbox.thread.created,email.received", hit.query("types"));
      assertEquals("text/event-stream", hit.header("accept"));
      assertTrue(closed.await(5, TimeUnit.SECONDS), "the server saw the stream close");
    }

    @Test
    void isNotCutByTheRequestTimeoutOnceOpen() {
      mock.stream(
          "GET /inboxes/{inbox_id}/events",
          w -> {
            Thread.sleep(300);
            w.write("id: 1\ndata: {}\n\n");
            w.end();
          });
      List<InboxStreamItem> events = new ArrayList<>();
      try (EventStream stream =
          builder()
              .timeout(Duration.ofMillis(100))
              .build()
              .inboxes()
              .events()
              .streamInbox(ID, EventStreamOptions.builder().reconnect(false).build())) {
        stream.forEach(events::add);
      }
      assertEquals(1, events.size());
    }

    @Test
    void reconnectsWithLastEventIdAfterADrop() {
      mock.stream(
          "GET /inboxes/{inbox_id}/events",
          w -> {
            w.write("retry: 10\nid: 41\ndata: {\"n\":41}\n\n");
            w.end();
          });
      mock.stream(
          "GET /inboxes/{inbox_id}/events",
          w -> {
            w.write("id: 42\ndata: {\"n\":42}\n\n");
            w.end();
          });
      mock.reply("GET /inboxes/{inbox_id}/events", 503, ContractMock.error(503, "unavailable", "Busy"));
      mock.reply("GET /inboxes/{inbox_id}/events", 503, ContractMock.error(503, "unavailable", "Busy"));
      List<Integer> data = new ArrayList<>();
      EventStream stream =
          client()
              .inboxes()
              .events()
              .streamInbox(
                  "support@example.com",
                  Map.of("types", "inbox.email.received"),
                  EventStreamOptions.builder()
                      .lastEventId("40")
                      .reconnectDelay(Duration.ofSeconds(5))
                      .maxReconnects(1)
                      .build());
      ApiConnectionException error =
          assertThrows(
              ApiConnectionException.class,
              () -> {
                try (stream) {
                  for (InboxStreamItem item : stream) {
                    data.add(item.data().get("n").asInt());
                  }
                }
              });
      assertTrue(error.getMessage().contains("gave up after 1 reconnects"), error.getMessage());
      assertEquals(List.of(41, 42), data);
      List<ContractMock.Hit> hits = mock.hitsFor("GET /inboxes/{inbox_id}/events");
      assertEquals(List.of("40", "41", "42", "42"), hits.stream().map(h -> h.header("last-event-id")).collect(Collectors.toList()));
      assertTrue(hits.stream().allMatch(h -> "inbox.email.received".equals(h.query("types"))));
      assertTrue(hits.stream().allMatch(h -> h.rawPath.equals("/inboxes/support%40example.com/events")));
      assertEquals(List.of(10L, 10L, 10L), sleeps, "the server's retry field sets the wait");
    }

    @Test
    void keepsAQuietStreamGoingThroughEveryScheduledCloseAndCountsOnlyFailedOpens() {
      // The server closes a quiet stream about every ten minutes: eight in a row, each opened
      // and ended with nothing but keep-alives, are no failure with maxReconnects(2).
      for (int i = 0; i < 8; i++) {
        mock.stream(
            "GET /inboxes/events",
            w -> {
              w.write(": keep-alive\n\n");
              w.end();
            });
      }
      // A failed open between two that open does not add up either.
      mock.reply("GET /inboxes/events", 503, ContractMock.error(503, "unavailable", "Busy"));
      mock.stream(
          "GET /inboxes/events",
          w -> {
            w.write("id: 7\ndata: {\"n\":7}\n\n");
            w.end();
          });
      List<Integer> data = new ArrayList<>();
      try (EventStream stream =
          client()
              .inboxes()
              .events()
              .stream(null, EventStreamOptions.builder().maxReconnects(2).build())) {
        for (InboxStreamItem item : stream) {
          data.add(item.data().get("n").asInt());
          break;
        }
      }
      assertEquals(List.of(7), data);
      assertEquals(10, mock.hitsFor("GET /inboxes/events").size());
    }

    @Test
    void givesUpAfterMaxReconnectsThatFailToOpenInARow() {
      mock.stream(
          "GET /inboxes/events",
          w -> {
            w.write(": keep-alive\n\n");
            w.end();
          });
      for (int i = 0; i < 3; i++) {
        mock.reply("GET /inboxes/events", 503, ContractMock.error(503, "unavailable", "Busy"));
      }
      ApiConnectionException error =
          assertThrows(
              ApiConnectionException.class,
              () -> {
                try (EventStream stream =
                    client()
                        .inboxes()
                        .events()
                        .stream(null, EventStreamOptions.builder().maxReconnects(2).build())) {
                  stream.forEach(item -> {});
                }
              });
      assertTrue(error.getMessage().contains("gave up after 2 reconnects"), error.getMessage());
      assertEquals(4, mock.hitsFor("GET /inboxes/events").size());
    }

    @Test
    void retriesAReconnectRefusedWith503AndThrowsARefusalThatRetryingCannotFix() {
      mock.stream(
          "GET /inboxes/events",
          w -> {
            w.write("retry: 1\nid: 1\ndata: {}\n\n");
            w.end();
          });
      mock.reply("GET /inboxes/events", 503, ContractMock.error(503, "unavailable", "Busy"));
      mock.reply("GET /inboxes/events", 403, ContractMock.error(403, "restricted_api_key", "Team-wide key needed"));
      EventStream stream = client().inboxes().events().stream();
      PermissionDeniedException error =
          assertThrows(
              PermissionDeniedException.class,
              () -> {
                try (stream) {
                  stream.forEach(item -> {});
                }
              });
      assertEquals(403, error.getStatusCode());
      assertEquals("restricted_api_key", error.getName());
      assertEquals(
          Arrays.asList(null, "1", "1"),
          mock.hitsFor("GET /inboxes/events").stream().map(h -> h.header("last-event-id")).collect(Collectors.toList()));
    }

    @Test
    void throwsTheRefusalWhenTheStreamCannotOpen() {
      mock.reply("GET /inboxes/events", 403, ContractMock.error(403, "restricted_api_key", "No"));
      PermissionDeniedException error =
          assertThrows(PermissionDeniedException.class, () -> client().inboxes().events().stream());
      assertEquals(403, error.getStatusCode());
      assertEquals("restricted_api_key", error.getName());
    }

    @Test
    void retriesTheFirstConnectionLikeAnyGet() {
      mock.reply("GET /inboxes/events", 503, ContractMock.error(503, "unavailable", "Busy"), Map.of("retry-after", "0"));
      try (EventStream stream =
          client().inboxes().events().stream(EventStreamOptions.builder().reconnect(false).build())) {
        stream.forEach(item -> {});
      }
      assertEquals(2, mock.hitsFor("GET /inboxes/events").size());
    }

    @Test
    void refusesAnAnswerThatIsNotAnEventStream() {
      mock.reply("GET /inboxes/events", 200, Map.of("hello", "world"));
      BillionSendException error =
          assertThrows(BillionSendException.class, () -> client().inboxes().events().stream());
      assertEquals(200, error.getStatusCode());
      assertEquals("application_error", error.getName());
      assertTrue(error.getMessage().startsWith("Expected an event stream"), error.getMessage());
    }

    @Test
    void handsOutStreamGapOncePerPositionTypedWithoutMovingThePosition() {
      String gap = "event: stream.gap\ndata: {\"reason\":\"retention\"}\n\n";
      mock.stream(
          "GET /inboxes/events",
          w -> {
            w.write("retry: 1\n\n" + gap);
            w.end();
          });
      mock.stream(
          "GET /inboxes/events",
          w -> {
            w.write(gap);
            w.write(
                "id: 9.1.1790000000\nevent: inbox.email.received\ndata: {\"type\":\"inbox.email.received\",\"created_at\":\"2026-11-16T00:00:00.000Z\",\"inbox_id\":\"i\",\"data\":{\"inbox_id\":\"i\",\"thread_id\":\"t\",\"email_id\":\"e\"}}\n\n");
            w.end();
          });
      mock.stream(
          "GET /inboxes/events",
          w -> {
            w.write(gap);
            w.end();
          });
      mock.reply("GET /inboxes/events", 403, ContractMock.error(403, "restricted_api_key", "stop"));
      List<InboxStreamItem> items = new ArrayList<>();
      EventStream stream =
          client().inboxes().events().stream(EventStreamOptions.builder().lastEventId("1.1.1").build());
      assertThrows(
          PermissionDeniedException.class,
          () -> {
            try (stream) {
              stream.forEach(items::add);
            }
          });
      assertEquals(
          List.of("stream.gap:null", "inbox.email.received:9.1.1790000000", "stream.gap:null"),
          items.stream().map(i -> i.event() + ":" + i.id()).collect(Collectors.toList()));
      StreamGapEvent gapItem = assertInstanceOf(StreamGapEvent.class, items.get(0));
      assertEquals("retention", gapItem.reason());
      InboxStreamEvent email = assertInstanceOf(InboxStreamEvent.class, items.get(1));
      assertEquals("e", email.data().path("data").path("email_id").asText());
      assertEquals(
          List.of("1.1.1", "1.1.1", "9.1.1790000000", "9.1.1790000000"),
          mock.hitsFor("GET /inboxes/events").stream().map(h -> h.header("last-event-id")).collect(Collectors.toList()));
    }

    @Test
    void endsOneInboxsStreamAfterInboxDeletedWithoutReconnectingTheTeamStreamGoesOn() {
      mock.stream(
          "GET /inboxes/{inbox_id}/events",
          w -> {
            w.write("id: 1\nevent: inbox.deleted\ndata: {\"type\":\"inbox.deleted\",\"created_at\":\"x\",\"inbox_id\":\"i\",\"data\":{}}\n\n");
            w.end();
          });
      List<String> seen = new ArrayList<>();
      try (EventStream one = client().inboxes().events().streamInbox(ID)) {
        one.forEach(item -> seen.add(item.event()));
      }
      assertEquals(List.of("inbox.deleted"), seen);
      assertEquals(1, mock.hitsFor("GET /inboxes/{inbox_id}/events").size());

      mock.stream(
          "GET /inboxes/events",
          w -> {
            w.write("id: 1\nevent: inbox.expired\ndata: {\"type\":\"inbox.expired\",\"created_at\":\"x\",\"inbox_id\":\"i\",\"data\":{}}\n\n");
            w.write("id: 2\nevent: inbox.created\ndata: {\"type\":\"inbox.created\",\"created_at\":\"x\",\"inbox_id\":\"j\",\"data\":{}}\n\n");
          });
      List<String> names = new ArrayList<>();
      try (EventStream team = client().inboxes().events().stream()) {
        for (InboxStreamItem item : team) {
          names.add(item.event());
          if (names.size() == 2) {
            break;
          }
        }
      }
      assertEquals(List.of("inbox.expired", "inbox.created"), names);
    }

    @Test
    void endsQuietlyOnCloseInTheLoopAndRefusesASecondIteration() {
      mock.stream("GET /inboxes/events", w -> w.write("id: 1\ndata: {}\n\n"));
      EventStream stream = client().inboxes().events().stream();
      List<String> seen = new ArrayList<>();
      for (InboxStreamItem item : stream) {
        seen.add(item.id());
        stream.close();
      }
      assertEquals(List.of("1"), seen);
      assertThrows(IllegalStateException.class, stream::iterator);
    }

    @Test
    void endsQuietlyWhenClosedFromAnotherThreadWhileWaiting() throws Exception {
      mock.stream("GET /inboxes/events", w -> w.write("id: 2\ndata: {}\n\n"));
      EventStream stream = client().inboxes().events().stream();
      List<String> seen = new CopyOnWriteArrayList<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      CountDownLatch first = new CountDownLatch(1);
      Thread reader =
          new Thread(
              () -> {
                try {
                  for (InboxStreamItem item : stream) {
                    seen.add(item.id());
                    first.countDown();
                  }
                } catch (Throwable t) {
                  failure.set(t);
                }
              });
      reader.start();
      assertTrue(first.await(5, TimeUnit.SECONDS));
      Thread.sleep(100); // the reader is now blocked on the next event
      stream.close();
      reader.join(5_000);
      assertTrue(!reader.isAlive(), "the loop ended");
      assertNull(failure.get());
      assertEquals(List.of("2"), seen);
    }
  }
}
