package com.billionsend;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The stream resumed from a position older than the server keeps (24 hours): events may be
 * missing, so read the REST lists again. It has no id and does not move the stream position. One
 * stream hands it out once per position: a later reconnect from the same position, which the
 * server answers with another gap, does not repeat it.
 *
 * @param data {@code {reason}}, such as {@code {"reason": "retention"}}
 */
public record StreamGapEvent(JsonNode data) implements InboxStreamItem {
  /** The event name of a gap. */
  public static final String EVENT = "stream.gap";

  /** Always {@code null}: a gap is not a stream position. */
  @Override
  public String id() {
    return null;
  }

  /** Always {@code stream.gap}. */
  @Override
  public String event() {
    return EVENT;
  }

  /** Why events may be missing, such as {@code retention}; {@code null} when the server gave none. */
  public String reason() {
    JsonNode reason = data == null ? null : data.get("reason");
    return reason != null && reason.isTextual() ? reason.asText() : null;
  }
}
