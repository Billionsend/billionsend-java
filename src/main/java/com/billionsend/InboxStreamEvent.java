package com.billionsend;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One inbox event.
 *
 * @param id the stream position: keep the last one to resume with {@link
 *     EventStreamOptions.Builder#lastEventId(String)}
 * @param event the webhook event name: {@code inbox.email.received}, {@code inbox.thread.created},
 *     {@code inbox.draft.sent}, {@code email.received}, ...
 * @param data {@code {type, created_at, inbox_id, data}}: the webhook payload's metadata, whose
 *     {@code data} holds the ids to read next ({@code thread_id}, {@code email_id}, {@code
 *     draft_id}); never a body, a code or a link
 */
public record InboxStreamEvent(String id, String event, JsonNode data) implements InboxStreamItem {}
