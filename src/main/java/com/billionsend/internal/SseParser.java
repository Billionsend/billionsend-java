package com.billionsend.internal;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Parses a {@code text/event-stream} body (UTF-8) into events, following the WHATWG rules: lines
 * end in CRLF, LF or CR and may be split anywhere across chunks, a leading byte order mark is
 * dropped, comment lines and unknown fields are skipped, several {@code data} lines join with
 * newlines, and an event the stream cut off before its blank line is discarded.
 */
public final class SseParser {
  private final CharsetDecoder decoder =
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE);
  private final IntConsumer onRetry;
  private final StringBuilder buffer = new StringBuilder();
  private ByteBuffer leftover = ByteBuffer.allocate(0);
  private boolean first = true;
  private String lastId;
  private String eventName = "";
  private final List<String> dataLines = new ArrayList<>();
  private boolean hasData;

  /**
   * @param onRetry receives each valid {@code retry} field, in milliseconds
   * @param initialId the position before the first event (events without an {@code id} carry it)
   */
  public SseParser(IntConsumer onRetry, String initialId) {
    this.onRetry = onRetry;
    this.lastId = initialId;
  }

  /**
   * Feeds the next bytes of the body.
   *
   * @param bytes the buffer
   * @param offset where the bytes start
   * @param length how many there are
   * @return the events completed by these bytes
   */
  public List<ServerSentEvent> feed(byte[] bytes, int offset, int length) {
    ByteBuffer in = ByteBuffer.allocate(leftover.remaining() + length);
    in.put(leftover).put(bytes, offset, length).flip();
    CharBuffer out = CharBuffer.allocate(in.remaining() + 2);
    decoder.decode(in, out, false);
    leftover = in.slice();
    append(out);
    return takeLines(false);
  }

  /**
   * Ends the body: completes the last line, and drops an event not ended by a blank line.
   *
   * @return the events completed by the end of the body
   */
  public List<ServerSentEvent> finish() {
    CharBuffer out = CharBuffer.allocate(leftover.remaining() + 2);
    decoder.decode(leftover, out, true);
    decoder.flush(out);
    leftover = ByteBuffer.allocate(0);
    append(out);
    return takeLines(true);
  }

  private void append(CharBuffer out) {
    out.flip();
    buffer.append(out);
    if (first && buffer.length() > 0) {
      if (buffer.charAt(0) == '﻿') {
        buffer.deleteCharAt(0);
      }
      first = false;
    }
  }

  private List<ServerSentEvent> takeLines(boolean last) {
    List<ServerSentEvent> events = new ArrayList<>();
    int start = 0;
    while (true) {
      int index = -1;
      for (int i = start; i < buffer.length(); i++) {
        char c = buffer.charAt(i);
        if (c == '\r' || c == '\n') {
          index = i;
          break;
        }
      }
      if (index == -1) {
        break;
      }
      int end = index + 1;
      if (buffer.charAt(index) == '\r') {
        // A lone CR at the end of the buffer may be the first half of a CRLF.
        if (index == buffer.length() - 1 && !last) {
          break;
        }
        if (index + 1 < buffer.length() && buffer.charAt(index + 1) == '\n') {
          end = index + 2;
        }
      }
      ServerSentEvent event = processLine(buffer.substring(start, index));
      if (event != null) {
        events.add(event);
      }
      start = end;
    }
    buffer.delete(0, start);
    return events;
  }

  private ServerSentEvent processLine(String line) {
    if (line.isEmpty()) {
      if (!hasData) {
        eventName = "";
        return null;
      }
      String raw = String.join("\n", dataLines);
      ServerSentEvent event =
          new ServerSentEvent(lastId, eventName.isEmpty() ? "message" : eventName, parse(raw), raw);
      eventName = "";
      dataLines.clear();
      hasData = false;
      return event;
    }
    if (line.startsWith(":")) {
      return null;
    }
    int colon = line.indexOf(':');
    String field = colon == -1 ? line : line.substring(0, colon);
    String value = colon == -1 ? "" : line.substring(colon + 1);
    if (value.startsWith(" ")) {
      value = value.substring(1);
    }
    switch (field) {
      case "event":
        eventName = value;
        break;
      case "data":
        dataLines.add(value);
        hasData = true;
        break;
      case "id":
        if (value.indexOf('\u0000') == -1) {
          lastId = value;
        }
        break;
      case "retry":
        if (value.matches("\\d+")) {
          try {
            onRetry.accept(Integer.parseInt(value));
          } catch (NumberFormatException ignored) {
            // Too large to be a delay worth keeping.
          }
        }
        break;
      default:
        break;
    }
    return null;
  }

  private static JsonNode parse(String raw) {
    try {
      JsonNode node = Json.MAPPER.readTree(raw);
      return node == null || node.isMissingNode() ? TextNode.valueOf(raw) : node;
    } catch (JacksonException e) {
      return TextNode.valueOf(raw);
    }
  }
}
