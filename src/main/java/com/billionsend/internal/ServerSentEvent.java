package com.billionsend.internal;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One server-sent event, as the parser reads it.
 *
 * @param id the stream position after this event (the last {@code id} field seen), to resume with
 * @param event the event name ({@code message} when the server sent none)
 * @param data {@code data} parsed as JSON; a text node with the raw text when it is not JSON
 * @param raw the raw {@code data} text
 */
public record ServerSentEvent(String id, String event, JsonNode data, String raw) {}
