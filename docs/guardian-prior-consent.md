# Retained prior guardian consent: administrator review

This BE path supports explicit consent already collected and retained before a child's first guardian request. The user identified face-to-face verification (`IN_PERSON`) and previously confirmed that explicit consent records are retained. Identity verification alone does not replace the required explicit-consent, relationship, notice and scope checks. A stored record is not automatically accepted. The original collection method, current notice and scope coverage, subject binding, legal guardian relationship/contact, authenticity/accessibility, remaining validity and absence of withdrawal must be reviewed. No email reply, web declaration or legal verification provider is fabricated. Ordinary guardian intake and confirmation remain available under their existing policy.

## Activation decisions still required

The new path is **OFF by default**. The actual source is now identified as `IN_PERSON`; the governing reuse policy, maximum age and validity rules have not been established by this implementation. Before enabling it, the responsible team must review the records without placing child data or documents in Git, approve the `IN_PERSON` reuse procedure, confirm coverage of the current notice and scopes, set maximum record age and validity rules, and establish withdrawal checks and external record access/erasure procedures. `SIGNED_DOCUMENT` identifies a source format; its enum presence is not a legal determination that any particular document is sufficient.

Properties under `edupilot.guardian.team.prior-consent`:

| Property | Default / meaning |
| --- | --- |
| `enabled` | `false` |
| `policy-confirmed` | `false`; explicit review of this reuse policy |
| `methods` | empty; reviewed subset of `EMAIL_REPLY`, `PHONE`, `SIGNED_DOCUMENT`, `IN_PERSON` |
| `policy-reference` | unset; opaque reference to the reviewed policy, `[A-Za-z0-9_.:-]{1,100}` |
| `max-age` | unset; positive duration from actual consent receipt |

No production values or default age are supplied. Base team-review readiness is still required. Enabling an incomplete prior-consent policy fails activation. Enabled policy settings join the existing approval configuration digest, so changing, enabling or disabling them invalidates approvals bound to the previous snapshot, including ordinary team approvals. Plan the transition and re-review cases explicitly. When this path remains disabled, the existing configuration digest is preserved. Adding the `IN_PERSON` enum alone also preserves an already enabled policy that still selects the same older methods; only an actual policy-setting change changes the digest. Golden snapshots and an existing TEAM approval gate regression cover both cases.

## API contract

Both endpoints use the existing ADMIN URL/method checks and recheck the current ACTIVE ADMIN plus `reviewer-ids` allowlist in the transaction. Responses are `Cache-Control: no-store`, `Referrer-Policy: no-referrer`. No self approval is permitted.

`GET /api/admin/guardian-requests/prior-consent/options` returns `available`, current `noticeVersion`, `noticeDigest`, `noticeUrl`, allowed `methods`, `maxAge` (ISO-8601 duration), `requiredScopes` and `optionalAiScope`. Disabled prior review returns `available=false` and empty/null options. An unready base team policy returns 503. No case or contact information is created by the read.

`POST /api/admin/guardian-requests/users/{userId}/prior-consent/approval` accepts:

```json
{
  "idempotencyKey": "synthetic-review-001",
  "requestId": null,
  "generation": 0,
  "revision": 0,
  "method": "IN_PERSON",
  "evidenceReference": "synthetic-record-001",
  "responseReceivedAt": "2026-10-27T01:00:00Z",
  "consentValidUntil": "2026-10-30T01:00:00Z",
  "noticeVersion": "synthetic-v1",
  "noticeDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "relationship": "PARENT",
  "scopes": ["SERVICE"],
  "subjectMatched": true,
  "responseExplicitlyConsents": true,
  "legalGuardianDeclarationConfirmed": true,
  "guardianContactChecked": true,
  "noticeAndScopesMatched": true,
  "confirmationMethodChecked": true,
  "evidenceReferenceChecked": true,
  "validityChecked": true,
  "notWithdrawn": true
}
```

The example is synthetic and does not configure an accepted collection method or grant permission to use a real record. Each boolean is required to be true because the reviewer has performed that specific check; a UI must never preselect or silently submit them as checked. `evidenceReferenceChecked` includes verifying that the retained original can be accessed by authorized reviewers. `validityChecked` includes review of any original expiry/conditions. `notWithdrawn` requires checking the responsible record/withdrawal register at review time. `subjectMatched` binds the actual child and consent subject to this account without pretending that an older record contained a future request ID. Accept only an opaque reference, not a document, email body, child/guardian name, contact, public URL or signed download URL.

For an account with no case, send null `requestId` and zero generation/revision. The service creates the first case, records the review and approves in one transaction. For an existing first pending case, send its exact ID/generation/revision from detail. Active, non-legacy guardian-required DOB eligibility is required; adult, legacy, missing/invalid DOB, inactive and self targets do not get this approval.

The actual receipt must be **before** the first generation's start, not in the future, and within the reviewed `max-age`. `consentValidUntil` must be in the future. Current notice version/digest and SERVICE scope are mandatory. Existing web-declared relationship/scopes cannot be expanded or changed by this route. Additional EXTERNAL_AI is accepted only when separately configured and explicitly covered by the record.

Approval expiry is the earliest of server review time + ordinary `approval-validity`, supplied record validity end, and actual receipt + reviewed `max-age`. For `IN_PERSON`, `responseReceivedAt` is the actual face-to-face consent confirmation date/time; it must never be replaced by the later administrator processing time. Server review time is stored separately from actual receipt time. Previously terminal cases and generations after the first are refused; previous revocation, refusal or withdrawal cannot be bypassed by replaying the old record. Fresh ordinary consent remains the path for those cases. Expired first cases require ordinary restart rather than silent extension.

The response uses the existing `Status` shape. `serviceApproved` reflects guardian approval, not completion of email verification. The business access gate still requires verified email; external AI additionally requires its explicit scope. Feature flags and FE availability are independent and remain unchanged by these endpoints.

Errors use existing codes: 400 `VALIDATION_FAILED` for absent/inconsistent evidence or unchecked facts, 401/403 authentication/current reviewer failure, 409 `GUARDIAN_STATE_CONFLICT` for stale or ineligible case state and 409 notice/configuration changes, 503 `GUARDIAN_TEAM_UNAVAILABLE` when disabled/unready. On conflict, reload and re-review; never advance a revision automatically. Same key+same payload retries return the current case without another grant, event or consent epoch change. Same key+different payload and competing fresh approvals conflict. A retry after withdrawal cannot restore approval.

## Evidence, audit and recovery

V65 remains an unpublished local migration in this change. Its CHECK permits both existing source methods and `IN_PERSON`; no previously committed or deployed migration is edited. V65 adds nullable `prior_consent_method`, `evidence_reviewed_at`, `prior_consent_valid_until` only. It never approves, changes or backfills existing users. New confirmations store `confirmationMethod=PRIOR_RECORD` plus the actual source method, including `IN_PERSON`. The ordinary `replyChannel` still describes the standard workflow and must not be used to relabel an in-person record as an email reply or phone call. Admin detail adds nullable `priorConsentEvidence` with `method`, `receivedAt`, `reviewedAt`, `consentValidUntil`; opaque `evidenceReference` remains admin-only. Self/public responses do not expose these new provenance fields or the reference.

Audit events are `REVIEWER_REQUESTED` (only when a case was absent), `PRIOR_CONSENT_CONFIRMED`, `PRIOR_CONSENT_APPROVED`, with actor, generation/revision and server time. They contain no record body, reference or contact. Account locks precede case locks, reviewers/targets lock in sorted ID order, and the final approval replay key is retained under the existing bounded evidence period. Failure rolls back case creation and all grant/audit writes together.

Existing approval contact/token purge, finite evidence retention, revocation, withdrawal, deletion and expiry also clear the new provenance fields. External stored originals remain under the team's separately reviewed access, retention and withdrawal procedures; this BE does not upload, inspect or delete them. Do not run older binaries that cannot read `PRIOR_RECORD` after this value has been written; use a compatible reader or forward fix, not schema deletion.

## FE handoff and verification boundary

FE is unchanged. The admin UI needs an explicit disabled-by-default entry, options read, pending account/case selection, current case snapshot, genuine evidence review checks, separate actual receipt/validity fields, no-store handling and conflict/503 recovery. Display `IN_PERSON` as face-to-face confirmation, label the actual confirmation date separately from server review time, and show only the opaque reference to the retained record. Do not request an original document upload. A guardian request need not already exist after signup. Options `available=true` is necessary; the UI must still obtain an actual reviewed record before submitting. Existing learner guardian entry, email verification and optional AI displays remain distinct.

This implementation does not prove deployed FE support, production policy, original record sufficiency, live account approval or end-to-end release readiness. Local tests use synthetic H2 data and mocked mail/AI only. The existing isolated GitHub-hosted MySQL migration fixtures now expect V65; their actual execution requires a later authorized remote CI run and is not replaced by H2 results.


The user's 2026-10-30 plan is to conduct testing tentatively through the end of 2026. This is a planning horizon only: it does not establish consent expiry, the original record validity, a maximum reuse duration, or permission to enable the feature. Those policy values remain unset and the feature remains default OFF.
