package com.billionsend;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What iterating an inbox {@link EventStream} gives: an {@link InboxStreamEvent}, or a {@link
 * StreamGapEvent} when events may be missing. Tell them apart with {@code instanceof} or a switch
 * on {@link #event()}.
 */
public sealed interface InboxStreamItem permits InboxStreamEvent, StreamGapEvent {
  /** The stream position after this event, or {@code null} for a {@link StreamGapEvent}. */
  String id();

  /** The event name, such as {@code inbox.email.received}, or {@code stream.gap}. */
  String event();

  /** The event's JSON body. */
  JsonNode data();
}
