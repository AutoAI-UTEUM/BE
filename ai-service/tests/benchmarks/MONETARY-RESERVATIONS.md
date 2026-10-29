# Monetary reservation component (offline only)

`MonetaryBudget` and `MonetaryTransport` support a future bounded synthetic evaluation.
They do not enable a live run, load credentials, change provider settings, or establish
an upper bound on reasoning costs. Existing `run` remains a count-only benchmark and
must not be used for the approved USD5 evaluation until all billable bounds are proven.

The persistent SQLite ledger uses integer USD ticks (10**10 per USD). Creation rejects
more than USD5 or 20 attempts. Every transmission reserves its trusted worst-case cost
before I/O. Atomic reservations and a single shared path cover workers and retries;
separate ledger paths would represent separate budgets, so a supervisor must supply
one path for the entire evaluation. Reopening preserves reservations. Unknown outcomes,
network failures and zero-cost responses never release capacity. Actual usage is linked
through `response.extensions["monetary_attempt_id"]` and recorded separately. Summary
reads use one snapshot. A response above its claimed bound permanently blocks further
attempts; this detects an invalid premise after spending and does not retroactively
make it safe. Therefore the caller must prove bounds before allowing any real provider request.
The wrapper creates its own standard HTTP transport with retries=0; only an exact
standard MockTransport can be supplied for offline verification. Custom/retrying
inner transports are rejected so they cannot hide multiple sends behind a reservation.

`upper_bound(request)` must check the actual model/region/tariff, all billable input,
visible output, reasoning, function/tool tokens and additional charges. It must reject
unknown models, paid tool/file operations or unbounded tokens. Do not treat effort,
visible-output max_tokens/max_completion_tokens/max_output_tokens, a timeout, or
post-response usage as a reasoning-cost hard cap. No discount is needed for safe
reservation. `MonetaryTransport` restricts routes and reserves before transmission;
it does not itself validate this provider-specific proof or parse streamed usage.

Current documented provider findings (2026-10-04):

- https://docs.x.ai/developers/models/grok-4.5 : grok-4.5, 500k context,
  input USD2/M and output USD6/M.
- https://docs.x.ai/developers/pricing : verify long-context and region pricing
  before selecting a bound; do not assume the base rate applies everywhere.
- https://docs.x.ai/developers/model-capabilities/text/reasoning : grok-4.5 reasoning
  cannot be disabled; effort is not a numeric upper bound; reasoning is billable.
- Parent research reports the official Chat Completions/Responses references exclude
  reasoning/function tokens from visible-output limits. Until a reasoning-inclusive
  bound is established, the safe bound callback raises an error and paid calls stay off.

This component has concurrency, restart, cap exhaustion, unknown/zero usage,
once-only reconciliation, snapshot and pre-I/O failure regressions using mock HTTP.
It is evaluation infrastructure, not service-wide course/institution quota enforcement
and not full AI-04 acceptance.
