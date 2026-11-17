# BillionSend Java SDK

The official Java client for the [BillionSend](https://billionsend.com) email API: send email, run inboxes for people and agents, stream inbox events, verify webhooks, and manage domains, contacts and broadcasts.

- Every API operation, generated from the BillionSend OpenAPI document and named after its `operationId`.
- Answers as Jackson `JsonNode` trees; failures as unchecked exceptions you can tell apart by class.
- Safe sends: every send carries an `Idempotency-Key`, so retries after a timeout never send twice.
- Iterables for every cursor list and for live inbox events, with automatic resume.
- Java 17 or later, on `java.net.http.HttpClient`. One runtime dependency: `jackson-databind`.

Documentation: [billionsend.com/docs](https://billionsend.com/docs) · Source: [github.com/billionsend/billionsend-java](https://github.com/billionsend/billionsend-java)

## Install

Maven:

```xml
<dependency>
  <groupId>com.billionsend</groupId>
  <artifactId>billionsend-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.billionsend:billionsend-java:0.1.0")
```

## Quick start

Create an API key on the dashboard (it starts with `bs_`) and set `BILLIONSEND_API_KEY`, or pass it to the client:

```java
BillionSend client = new BillionSend(); // reads BILLIONSEND_API_KEY
BillionSend explicit = new BillionSend("bs_...");

JsonNode sent =
    client.emails().send(
        Map.of(
            "from", "Acme <hello@acme.dev>",
            "to", List.of("ana@example.com"),
            "subject", "Welcome to Acme",
            "html", "<p>Glad you are here.</p>"));
System.out.println("queued " + sent.get("id").asText());
```

Every operation is a method of a resource, reached through accessor methods that follow the operation's `operationId`: `emails.receiving.attachments.list` is `client.emails().receiving().attachments().list(emailId)`. One operation is also the group of others, and is that group's `get`: `emails.metrics` is `client.emails().metrics().get(query)` (see [Resources](#resources)). A client is thread-safe; make one and share it.

**Requests and answers.** This version has no class per schema. A request body is any value Jackson can write: a `Map`, a `JsonNode`, or a class of your own. Query parameters are a `Map<String, ?>`: `null` values are left out, and a `List` or array goes comma-separated in one key, as the API declares its list parameters. Every method returns the answer as a `JsonNode` (field names as the API spells them, in snake_case); read it with `path(...)` and `asText()`, or map it onto a class of your own with Jackson (`new ObjectMapper().treeToValue(node, MyType.class)`). The [API reference](https://billionsend.com/docs) describes every body and answer.

Each method has overloads: the full one takes the path parameters, then the body, then the query, then a `RequestOptions`; the shorter ones leave out what is optional.

## Authentication

The key comes from `new BillionSend("bs_...")`, `BillionSend.builder().apiKey(...)`, or the `BILLIONSEND_API_KEY` environment variable, and is sent as `Authorization: Bearer bs_...`. A call that needs a key throws an `AuthenticationException` named `missing_api_key` (with no status code, and no request made) when there is none. A key restricted to one inbox is what an agent should hold; see [API keys](https://billionsend.com/docs/api-keys).

An agent can create its own account with no key at all; the answer carries the new key and inbox:

```java
JsonNode signup =
    new BillionSend()
        .agent()
        .signup(Map.of("human_email", "owner@acme.dev", "name", "Support agent"));

BillionSend agent = new BillionSend(signup.get("api_key").asText());
System.out.println(signup.path("inbox").path("email_address").asText());
```

## Sending once

The sending operations (`emails().send`, `emails().batch`, `emails().receiving().reply` / `forward`, `inboxes().send`, `inboxes().drafts().send`, `inboxes().threads().emails().reply` / `forward`) always send an `Idempotency-Key` header. Without your own, each call gets a new random key and that call's retries reuse it, so a retry after a timeout cannot send twice. Pass your own key to make a send safe across calls and processes too; a repeat with the same key and payload returns the first result:

```java
client.emails().send(message, RequestOptions.builder().idempotencyKey("welcome/" + userId).build());
```

## An agent's inbox: wait, read, reply

An agent holds a key restricted to its inbox, waits for new mail on the live event stream, reads it, and replies in the thread:

```java
String inboxId = "support@acme.dev"; // an inbox id or its address

try (EventStream stream =
    client.inboxes().events().streamInbox(inboxId, Map.of("types", "inbox.email.received"))) {
  for (InboxStreamItem item : stream) {
    if (item instanceof StreamGapEvent) {
      // Resumed from too far back: events may be missing. Re-read the threads.
      continue;
    }
    JsonNode ids = item.data().path("data");
    String threadId = ids.path("thread_id").asText(null);
    String emailId = ids.path("email_id").asText(null);
    if (threadId == null || emailId == null) {
      continue;
    }

    JsonNode email = client.inboxes().threads().emails().get(inboxId, threadId, emailId);
    String answer = draftAnswer(email.path("subject").asText(), email.path("text").asText()); // your model
    client
        .inboxes()
        .threads()
        .emails()
        .reply(
            inboxId,
            threadId,
            emailId,
            Map.of("text", answer),
            // a replayed event cannot reply twice
            RequestOptions.builder().idempotencyKey("reply/" + emailId).build());
  }
}
```

Waiting for a sign-in code sent to the inbox:

```java
JsonNode code = client.inboxes().codes().latest("support@acme.dev", Map.of("from", "example.com", "wait", 30));
```

## Live events

`client.inboxes().events().stream()` streams every inbox event of the team (it needs a team-wide key); `client.inboxes().events().streamInbox(inbox)` streams one inbox. Both return once the stream is open (or throw the error that refused it) and give an `EventStream`: an `Iterable` of typed events to read once, and an `AutoCloseable` to close when done, best with try-with-resources.

- `InboxStreamEvent`: `id()`, `event()` and `data()`, where `event()` is the webhook event name (`inbox.email.received`, `inbox.thread.created`, `inbox.draft.sent`, `email.received`, ...) and `data()` is `{type, created_at, inbox_id, data}`, the webhook payload's metadata with the ids to read next (`thread_id`, `email_id`, `draft_id`).
- `StreamGapEvent`: `id()` is `null`, `event()` is `stream.gap` and `reason()` says why, when the stream resumed from further back than the server keeps (24 hours): events may be missing, so read the lists again. One stream hands it out once per position.

When the connection drops (the server also closes every stream about every ten minutes), the stream reconnects with `Last-Event-ID` and replays what it missed before the live events. It gives up after `maxReconnects` failures in a row (default 5) with an `ApiConnectionException`; a refusal a retry cannot fix (a revoked key) is thrown as its own class. One inbox's stream ends after that inbox's `inbox.deleted` or `inbox.expired`. `close()` from another thread ends a loop waiting for the next event, without an error.

```java
EventStreamOptions options =
    EventStreamOptions.builder()
        .lastEventId(saved) // resume where a previous run stopped
        .build();
try (EventStream stream =
    client.inboxes().events().stream(Map.of("types", "inbox.email.received,inbox.thread.created"), options)) {
  for (InboxStreamItem item : stream) {
    saved = stream.lastEventId();
    // break, or stream.close(), ends the stream
  }
}
```

## Pagination

List methods take the API's cursor parameters (`limit`, `after`, `before`) and return one page. Every cursor list also has a `listAll` (thread search: `searchAll`) that fetches pages as the loop asks for them:

```java
for (JsonNode contact : client.contacts().listAll(Map.of("limit", 100))) {
  System.out.println(contact.get("email").asText());
}

List<String> domainIds =
    client.domains().listAll().stream().map(d -> d.get("id").asText()).collect(Collectors.toList());
```

Starting with `before` walks backward. A failed page is thrown as its exception; an answer that is not a list page (no `data` array, or more pages but no `id` on the item to continue from, after that page's items) throws a `BillionSendException` named `application_error`. `new CursorIterable(fetchPage, query)` builds the same iterable over any page fetcher.

## Errors

Every method throws an unchecked exception of a class for its kind, all subclasses of `BillionSendException`:

| Class | When |
| --- | --- |
| `ValidationException` | `400`, `422`: the request is invalid |
| `AuthenticationException` | `401`, or no API key configured (`missing_api_key`, no request made) |
| `PermissionDeniedException` | `403`: the key, its scope, your role or your plan does not allow it |
| `NotFoundException` | `404` |
| `ConflictException` | `409`, such as an idempotency key reused with a different payload |
| `RateLimitException` | `429`; `getRetryAfter()` holds the server's hint in seconds |
| `ApiException` | `5xx` |
| `ApiConnectionException` | no answer: DNS, connection or TLS failure (`getStatusCode()` is `null`) |
| `ApiTimeoutException` | the request timed out (a subclass of `ApiConnectionException`) |
| `WebhookVerificationException` | a webhook delivery that does not verify |

Each carries `getStatusCode()`, `getName()` (the API's error name, such as `validation_error`; `application_error` when the API sent none), `getMessage()` and `getRequestId()`, the answer's `x-request-id`: the same id the request has on the dashboard's Logs page, so quote it when you ask about a request.

```java
try {
  JsonNode email = client.emails().get(id);
} catch (NotFoundException e) {
  // no such email
} catch (RateLimitException e) {
  System.out.println("retry in " + e.getRetryAfter() + " s");
} catch (BillionSendException e) {
  System.err.println(e.getStatusCode() + " " + e.getName() + " " + e.getMessage() + " " + e.getRequestId());
}
```

## Retries and timeouts

Failures worth retrying are retried up to `maxRetries` times (default 2): `429`, `5xx`, timeouts and network errors, and the `409 concurrent_idempotent_requests` the API answers while the first attempt with the same key is still running. Only requests safe to repeat are retried: `GET`, `PUT`, `DELETE` and the sending operations above (and any request you give an idempotency key). The client waits as `retry-after` asks, or backs off exponentially (0.5 s, 1 s, 2 s ... up to 8 s, with jitter); a `retry-after` over a minute is not waited for, and the error is thrown instead.

Each attempt has a timeout (default 60 seconds); an event stream is timed only until it opens. Interrupting the calling thread ends a call (and its retries) with a `BillionSendException`, and keeps the thread's interrupt flag.

```java
BillionSend patient = BillionSend.builder().maxRetries(4).timeout(Duration.ofSeconds(120)).build();
client.emails().send(message, RequestOptions.builder().maxRetries(0).timeout(Duration.ofSeconds(10)).build());
```

## Verify webhooks

Every webhook delivery is signed with the endpoint's `whsec_` secret (shown when you create the endpoint, and by `webhooks().rotate`). `WebhookVerifier.verify` checks the signature and the timestamp (within 5 minutes by default, so a captured delivery cannot be replayed) and returns the parsed event; anything wrong throws a `WebhookVerificationException`. Pass the body exactly as it arrived, before any JSON parsing, and the request headers as a map (names in any case; a value may be a `String`, a `List` or a `String[]`):

```java
int handle(byte[] rawBody, Map<String, List<String>> headers) {
  try {
    JsonNode event = WebhookVerifier.verify(rawBody, headers, System.getenv("BILLIONSEND_WEBHOOK_SECRET"));
    if (event.get("type").asText().equals("email.bounced")) {
      // ...
    }
    return 204;
  } catch (WebhookVerificationException e) {
    return 400;
  }
}
```

In Spring, `@RequestBody byte[] body` and `@RequestHeader HttpHeaders headers` fit as they are. During a secret rotation a delivery carries a signature for each live secret, and either secret verifies it.

## Configuration

```java
BillionSend configured =
    BillionSend.builder()
        .apiKey("bs_...") // default: BILLIONSEND_API_KEY
        .baseUrl("https://api.billionsend.com") // default: BILLIONSEND_BASE_URL, then this
        .timeout(Duration.ofSeconds(60)) // per attempt; event streams are timed only until they open
        .maxRetries(2) // see Retries and timeouts
        .header("x-my-header", "value") // sent with every request
        .httpClient(HttpClient.newHttpClient()) // default: a new one that does not follow redirects
        .build();
```

Every method takes a last `RequestOptions` argument with `idempotencyKey`, `header`, `timeout` and `maxRetries` for that call, and `batchValidation(BatchValidation.PERMISSIVE)` for `emails().batch` and `contacts().batch().create` (the `x-batch-validation` header). The event streams take `EventStreamOptions` instead: `lastEventId`, `reconnect`, `maxReconnects`, `reconnectDelay`, `timeout`, `maxRetries` and `header`.

## Resources

| Accessor | Covers |
| --- | --- |
| `emails()` | send, batch, list, get, update, cancel, delete, insights, `metrics()` (get, links, countries, clients) |
| `emails().receiving()` | received mail: list, get, delete, reply, forward, `attachments()` (list, get, text) |
| `domains()` | create, list, get, update, delete, verify |
| `apiKeys()` | create, list, delete |
| `contacts()` | create, list, get, update, delete, preferencesLink, `batch()` (create, get, remove), `topics()`, `segments()` |
| `audiences().contacts()` | contacts addressed through an audience |
| `contactProperties()`, `segments()`, `topics()` | create, list, get, update, delete (segment contacts under `segments().contacts()`) |
| `broadcasts()` | create, list, get, update, delete, send, duplicate, cancel |
| `templates()` | create, list, get, update, delete, publish, duplicate |
| `webhooks()` | create, list, get, update, delete, rotate |
| `suppressions()` | create, list, get, delete, `batch()` add / remove |
| `unsubscribes()` | create, list, delete |
| `verifications()` | availability, estimate, create, list, get |
| `usage()`, `deliverability()` | get |
| `inboxes()` | create, list, get, update, delete, send, `agent()`, `safety()`, `labels()`, `threads()` (search, labels, emails, reply, forward), `quarantine()`, `codes()`, `drafts()`, `events()` |
| `agent()` | signup, status, claimEmail |

One operation is also the group of others: `emails.metrics` is `client.emails().metrics().get(query)`, beside `emails.metrics.links` / `countries` / `clients` (`client.emails().metrics().links(query)` and so on).

## Development

The build and the tests need only a JDK and Maven; `scripts/container-mvn.sh` runs them in the pinned official Maven image instead, with nothing installed on the host.

```bash
python3 scripts/generate.py --check   # the generated sources match the snapshot
mvn -B verify                         # compile (warnings are errors) and run every test
scripts/container-mvn.sh              # the same verify, in a container
```

The contract is `openapi/api.openapi.json`, a snapshot of the published OpenAPI document. `scripts/generate.py` writes the resource classes from it (`src/main/java/com/billionsend/resources/` and `BillionSendApi.java`; never edit them by hand). The tests run every SDK method against a local server that checks each request against the snapshot (path and query parameters, headers and the body, against their JSON Schemas) and answers from it, once with answers that carry every property and once with only the required ones; they fail when an operation has no SDK method, or a method reaches an endpoint the snapshot does not have. When the API changes:

```bash
python3 scripts/sync_openapi.py                 # or: --from path/to/api.openapi.json
python3 scripts/generate.py                     # rewrites the resource classes
mvn -B verify                                   # what fails is what the SDK has to follow
```

## Releasing

Publishing is manual; nothing is published on push. The package goes to Maven Central through the Sonatype Central Portal.

What the release needs, once:

- A [Central Portal](https://central.sonatype.com) account that owns the namespace `com.billionsend`, verified with the DNS `TXT` record the portal asks for on `billionsend.com`.
- A Central Portal user token (Account → Generate User Token), stored as the secrets `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.
- A GPG signing key: the ASCII-armored private key (`gpg --armor --export-secret-keys <key id>`) as the secret `GPG_PRIVATE_KEY`, its passphrase as `GPG_PASSPHRASE`, and the public key published to a key server Central reads (`gpg --keyserver keys.openpgp.org --send-keys <key id>`).

The secrets can sit in the repository or in its `maven-central` environment, which the workflow names (add reviewers to the environment to gate releases).

Every version, the first included:

1. Set the version in `pom.xml` and in `BillionSend.VERSION` (a test fails when they differ, or when `CHANGELOG.md` has no section for it), turn the changelog's `Unreleased` heading into that version, commit, then tag and push: `git tag v0.1.0 && git push origin main v0.1.0`.
2. Actions → `release` → Run workflow, on the version tag. The workflow refuses a branch or a tag that differs from `pom.xml`, checks the generated sources, runs every test, builds the jar, sources and javadoc jars, signs everything and uploads it to Central, waiting until the version is published.

`mvn -B verify` alone builds the unsigned release files in `target/`: `billionsend-java-<version>.jar`, `-sources.jar`, `-javadoc.jar` and the pom. Without the workflow, the same release from a clean checkout of the tag is `mvn -B -P release deploy`, with the token in `~/.m2/settings.xml` (a `<server>` with id `central`), the signing key in the local GPG keyring and its passphrase in `MAVEN_GPG_PASSPHRASE`.

## License

[MIT](LICENSE)
