# Changelog

## 0.1.2 (2026-11-17)

- An event stream's reconnect that the API refuses with a `Retry-After` (a 429 or a 5xx) waits that long before the next attempt, and one longer than a minute is thrown at once. When the reconnects run out, the last refusal is thrown as itself (`RateLimitException`, `ApiException`, ...); `ApiConnectionException` ("gave up after N reconnects") is left for a stream that could not reach the API at all.

## 0.1.1 (2026-11-17)

- An event stream no longer gives up on a quiet inbox. The server closes every stream about every ten minutes; each such close counted as a failure, so a stream with no events for about an hour threw `gave up after 5 reconnects`. Only reconnects that fail to open count now, in a row, and one that opens resets the count.
- README: the Spring webhook example takes the headers as `@RequestHeader Map<String, String>`, which every framework version binds.

## 0.1.0 (2026-11-17)

- First version of the BillionSend SDK for Java: every operation of the BillionSend API (139 in this version), grouped by resource and named after each operation's `operationId` (`client.emails().receiving().attachments().list(...)`), generated from the OpenAPI document. `emails.metrics`, which is also the group of `emails.metrics.links` / `countries` / `clients`, is `client.emails().metrics().get(...)`.
- Request bodies as any value Jackson can write (a `Map`, a `JsonNode`, your own class); answers as Jackson `JsonNode` trees; query parameters as a `Map`, list values sent comma-separated in one key, as the document declares them.
- Errors as unchecked exceptions: `ValidationException`, `AuthenticationException`, `PermissionDeniedException`, `NotFoundException`, `ConflictException`, `RateLimitException` (with `getRetryAfter()`), `ApiException`, `ApiConnectionException` and `ApiTimeoutException`, all `BillionSendException`s with `getStatusCode()`, `getName()`, `getMessage()` and `getRequestId()` (the answer's `x-request-id`).
- The sending operations always carry an `Idempotency-Key`: the caller's, or a new one per call that its retries reuse.
- Retries for 429, 5xx, timeouts, network failures and `409 concurrent_idempotent_requests`, on requests safe to repeat; `retry-after` honoured, exponential backoff otherwise; per-attempt timeouts; per-call `RequestOptions`.
- `listAll` / `searchAll` iterables over cursor-paginated lists, also as a `Stream`; a page with no `data` array, or with more pages but no `id` to continue from, throws `application_error` instead of ending quietly.
- Inbox event streams (server-sent events) as closeable iterables of typed events (`InboxStreamEvent`, `StreamGapEvent`), resuming with `Last-Event-ID` after a dropped connection; `stream.gap` handed out once per position; one inbox's stream ends after its `inbox.deleted` or `inbox.expired`.
- `WebhookVerifier.verify` checks a webhook delivery's signature and timestamp and returns the parsed event.
- Java 17 or later; `java.net.http.HttpClient`; one runtime dependency, `jackson-databind`.
