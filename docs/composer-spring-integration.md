# Composer prediction: AI v1 names and Spring adapter

Local implementation based on develop `cdc3297b45647b069248b11393a2156a4929d0ef`
(AI #570 / issue #568). **OFF; no deployment or feature activation is authorized.**
Shared prediction field names follow the actual AI #570 v1.0 models, as requested.
The browser uses a saved-message selector and receives an SSE projection; those
application boundaries differ from the internal AI JSON API. See the concrete
[FE handoff contract](composer-prediction-fe-handoff.md). The adapter remains hidden
from OpenAPI and does not change the existing turn response or turn SSE. The admin
usage category is `COMPOSER_PREDICTION`; FE charts need a display label for it.

## Request and lifecycle

`POST /api/sessions/{sessionId}/composer-prediction`, JSON body
`{"sourceMessageId":123}`, bearer authentication, response `text/event-stream`.
The controller is absent unless `edupilot.composer.enabled=true`.
Call it separately only after the learning turn's save/completion and claim release.
The saved assistant message ID comes from `TurnResponse.messages[].id`. The existing
AI execution UUID is not stored on `ChatMessage`; it cannot be looked up safely.
The internal AI `sourceTurnId` is therefore the server-created opaque `message-{id}`.

The optional SSE has heartbeat comments and at most one `prediction` event:
`{"schemaVersion":"1.0","sourceTurnId":"message-123","contextVersion":"server-generated-sha256","suggestion":null,"warnings":[],"sourceMessageId":123}`.
`sourceMessageId` is the public selector/correlation field. `sourceTurnId` is the
server-created reference sent to AI, not the existing learning execution UUID.
`contextVersion` can be null when no eligible snapshot was accepted; this public
projection is not the strict internal AI response. Off/ineligible gating returns
null with empty warnings, matching AI's no-call behavior. Transient failures,
limits, duplicates and stale results use `PREDICTION_UNAVAILABLE` with a fixed safe
message. Provider warning text, usage, answer keys and internal reasons are omitted.
The validated security context is saved only as a request attribute for this SSE's
ASYNC redispatch. No HTTP session is created and no dispatcher authorization is
bypassed; the stream independently rechecks committed eligibility and ownership.
Provider failure is an optional null result; normal authentication, ownership and
eligibility failures before admission retain their existing HTTP error semantics.
An ineligible source/activity or disabled/unconfirmed policy makes zero AI calls.

FE work is separate: use an abortable authenticated fetch SSE request; cancel on
typing, page/session/source change, a new turn, opt-out, or unmount. Display only for
the matching source and client request generation, with an empty composer; require
explicit acceptance/editing and never auto-send. Render plain text, not HTML. Define
mobile, IME, keyboard, screen-reader and opt-out behavior before activation. This
change does not implement a FE or a durable user preference API.

## Authoritative context and gates

Spring rechecks the committed account role, guardian consent epoch, age/guardian
and external-AI eligibility, verified email, session ownership and material grant.
No admin exemption is added. The session must be ACTIVE with no active turn claim,
quiz or pending diagnosis and an EXPLAINED page. Only a latest, COMPLETED assistant
EXPLANATION/QA on that page after the conversation reset can be the source.
Quiz proposals and unknown UI actions are suppressed conservatively. Allowed actions
are the existing binary move-page, complete-session and note proposals with WAIT.

The current application has no explicit TEACHBACK turn/event or persistent phase.
The adapter does not accept a browser-supplied activity. Only the existing typed
EXPLANATION/QA paths qualify; DIAGNOSIS, REPAIR, generic/cancelled TEXT, all other
page states and future unknown/TEACHBACK UI actions fail closed. A future teachback
workflow must add an authoritative phase gate before being enabled; message text
classification is not an authoritative substitute. Do not reuse this adapter for a
teachback interaction hidden inside an ordinary QA workflow.

The bounded query returns only public EXPLANATION/QA plus USER questions explicitly
linked by `QaMessage`; arbitrary USER/TEXT may contain assessment answers and is
excluded. At most six chronological messages, 4,000 Unicode code points each and
6,000 total, keeping recent tails, are sent. PDFs, file IDs, page text, quizzes,
answers/rubrics, diagnosis/repair, notes, summaries, learner memory, email and IDs
of accounts/materials are never included in the AI request. Public text is still
user content; this is an allowlist of sources, not a PII-redaction guarantee.

Context version uses the committed optimistic session version, source, page and reset
count. Spring rereads access/version/context before HTTP, on the 500 ms heartbeat,
and before delivery. New turns, resets, moves, deletion, role/consent/grant changes
cancel/drop stale results. No session claim, session write or DB transaction is held
across the prediction network request. The original saved turn is never rolled back.

## Limits, durability and usage

Positive per-user policy values remain **unset**. `policy-confirmed` remains false.
The owner must choose daily attempts, rolling-minute attempts, concurrent leases,
daily USD-tick budget and a verified per-call reservation bound for the actual model,
input/output caps and current pricing. Test values in JUnit are synthetic fixtures,
not operational recommendations. Spring and AI each have an independent OFF flag.

Admission briefly locks the existing user row, checks the global AI quota/email gate
and composer limits, then commits an `AiUsageLog(COMPOSER_PREDICTION)` reservation
before network I/O. The existing unique 64-character `request_id` is a hash of user,
session and saved source message: the same answer cannot regenerate even after a
summary revision. Thus dedup and admission survive process restarts and serialize
across instances. No new migration or deployment-tool change is required.

No Compose/runtime environment forwarding or activation is added by this change.
Those remain a separate, explicitly authorized deployment/configuration step.

Failed, cancelled and unknown-spend attempts count. Daily boundaries use Asia/Seoul;
minute and concurrency windows cross midnight. A conservative fixed concurrency
lease lasts the configured total timeout plus one second, even if an attempt ends
early. A separate per-process technical cap is 32 connections, without a request
queue; per-user policy supports at most 8 leases and 1,000 daily attempts as code
safety caps. These caps do not activate or select business policy.

Known usage refines the same row in a new short transaction even for null/invalid/
stale/discarded responses. Known spend is retained when delivery fails. Success
denotes validated generation, not delivery or user acceptance. Unknown tokens/cost
stay null, never zero. For admission, unknown costs reserve the configured per-call
amount; observed cost is retained even above that
amount. Big-integer budget arithmetic avoids overflow. If admission storage fails,
no call starts. If finalization fails or the process crashes, the committed unknown
reservation remains charged conservatively for admission. Existing admin usage and
global call-count reports include these reserved attempts, not only successful calls.

The reservation must be a verified upper bound; application accounting cannot promise
a provider billing ceiling when pricing/model behavior changes. Physical provider
work can outlive a client cancellation. The lease bounds application HTTP concurrency,
not guaranteed remote computation. Budget/timeout/price observability and actual
per-user values need owner confirmation before activation.

## HTTP and failure isolation

The dedicated JDK async HTTP client uses the existing internal URL/token, never a
browser token, and sends the AI #570 v1.0 contract. It does not retry or follow redirects.
The response body is bounded to 16 KiB. Schema/echo, warning type and a single plain
line of at most 160 code points are validated before returning a suggestion.

Disconnect/error/completion, timeout, shutdown or stale/revoked access cancels the
underlying JDK HTTP future, including the wait for headers/body. Heartbeats make
disconnect detection possible independently of the original turn stream. Client,
proxy and servlet buffering can delay disconnect detection; cancellation is best
effort and does not prove the provider stopped or incurred no charge. Total deadline
is at most 10 seconds. All telemetry is metadata-only; no prompt/response is logged.

## Verification and remaining work

Tests use synthetic accounts/content, H2 and loopback MockWebServer only. They cover
source/phase/privacy/version gates, durable duplicate/concurrent admission, caller
rollback, unknown/known usage, quota windows, budget overflow, null/failure/stale
results, cancellation before headers and during the body, malformed/oversized HTTP
responses, and the default OFF configuration. Execution results are reported with
the change; no paid provider or deployed environment is used by these tests.

Still required before activation: FE integration of the documented adapter, owner-confirmed limit and
price/reservation values, explicit teachback phase support if that workflow exists,
FE consent/opt-out and stale-generation UX, and an authorized deployed integration
test of proxy buffering/cancellation and the real existing eligibility gates. No
push, PR, merge, deployment, operational data or permission changes are included.

The historical operations catalog retains the old `AiFeature.java` hash. Separate
local source verification links the current hash to an actually executed full offline
build and sanitized JUnit metadata; it is not CI or live acceptance evidence. The
source guard validates the new receipt without rewriting the historical catalog.
See [local source proof and Compose limitations](qa/composer-local-source-proof.md).
Local Docker-based checks and deployed/MySQL validation remain separate from the H2
and loopback results. Main CI also runs the new proof-validation unit tests; no
deployment workflow or permissions are changed.
