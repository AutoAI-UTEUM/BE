# Composer source verification: local evidence only

The historical operations catalog binds `AiFeature.java` to its earlier eleven
values. Adding `COMPOSER_PREDICTION` changes that source hash. The previous catalog,
63 migration hashes, historical confirmations and Guardian CI bindings remain intact.
Their canonical combined digest is pinned by the local-proof tests.

`evidence-map.json.localSourceBindings` explicitly separates a new **unsigned,
replayable local worktree test receipt** from `currentSourceBindings` (CI evidence).
It does not claim a new commit, a CI run, a MySQL run, provider access, deployed
acceptance, or permission to execute anything. The live manifest checker continues
to require its independent operator evidence. Local proof cannot fill that evidence.

## Generation

The repository generator runs the actual complete Main build using JDK 21, an already
populated workspace Gradle cache, `--offline --no-daemon --rerun-tasks`, two workers
and `-Dit.ai=false`. It invokes the wrapper JAR through the selected Java executable
with an argument array; it does not use an interpolated shell command. No distribution
download is attempted if the required cached distribution is absent. The child
environment excludes credentials and live-DB/provider settings, preserves essential
Windows keys case-insensitively, disables instance-metadata credentials, and points
AWS profile locations to absent files inside the ignored local run directory.

Example, with existing local paths chosen explicitly:

```text
python -I -S -B tools/qa/operations-acceptance-20261007/local-source-proof.py --java-home <existing-jdk21> --gradle-user-home <existing-workspace-gradle-cache>
```

The generator snapshots all Main source/test/resource inputs and wrapper/build files
before and after execution. Text CRLF is normalized to LF; binary files retain their
bytes. A changed snapshot, nonzero process exit, stale/missing JUnit output, report
counter mismatch, failed test, or skipped required suite prevents a passing receipt.
The required suites include the default-OFF application and all composer suites.
The feature compatibility test must actually pass: it preserves the eleven legacy
names and round-trips every enum value through a disposable H2 VARCHAR column and
the administrator JSON DTO. It is explicitly not a MySQL migration test.

Generated files remain under ignored `main-service/build/local-proof/<run-id>/`:
the log, source snapshot, sanitized per-case JUnit summary and local receipt. Existing
JUnit reports are copied before the run. Only the generated receipt and sanitized
summary are copied to `tools/qa/operations-acceptance-20261007/local-verification/`.
The catalog records their normalized-LF digests and the unchanged historical source
hash. The generator never edits the historical catalog or invents a CI URL. Raw logs
and XML stay local; public metadata includes only test names/status/counts and hashes.

## Verification

`local-source-proof.mjs` reads only local files, verifies containment and artifact
digests, recomputes the current source inventory, checks the generator version/hash,
execution window/profile and every JUnit status/count, and requires the compatibility
case. Failed or unknown cases cannot be relabeled as skipped or passed. Mandatory
tests cannot be skipped. The full build's other skipped tests remain explicitly
reported as unexecuted. It rejects CI/live/deployment claims and does not grant
operator acceptance. The receipt is local, unsigned evidence, not a remote attestation;
normal branch CI must still execute after publication is authorized.

The earlier 2026-11-04 run executed the full build with 1,224 unchanged source
inputs: 2,303 tests, 2,272 passed, 31 explicitly skipped, and zero failures/errors.
The [local receipt](../../tools/qa/operations-acceptance-20261007/local-verification/composer-spring-20261104.receipt.json)
and [sanitized JUnit metadata](../../tools/qa/operations-acceptance-20261007/local-verification/composer-spring-20261104.tests.json)
bind that earlier worktree snapshot and remain unchanged under historical local
bindings. The AI-v1 naming update has a separate current
[receipt](../../tools/qa/operations-acceptance-20261007/local-verification/composer-ai-v1-20261104.receipt.json)
and [JUnit metadata](../../tools/qa/operations-acceptance-20261007/local-verification/composer-ai-v1-20261104.tests.json):
2,305 tests, 2,274 passed, 31 skipped, zero failures/errors. A guard rejects reuse of
the earlier local proof for the changed source tree. No remote CI run or deployed
verification is claimed for either local receipt.

```text
python -I -S -B tools/qa/operations-acceptance-20261007/test_local_source_proof.py
node --test tools/qa/operations-acceptance-20261007/local-source-proof.test.mjs tools/qa/operations-acceptance-20261007/check-manifest.test.mjs
```

## Compose exit 125

The earlier DEV preservation tests started a local process and observed exit 125
from the `compose ... config --format json` step. Its result-error assertion passed;
the nonzero-status assertion failed before JSON parsing. The test did not retain or
display stderr, so the available output does not establish a plugin, configuration,
daemon, or permission subcause. The command requested configuration rendering, with
synthetic input and an isolated `DOCKER_CONFIG`; it did not request a daemon mutation,
SSH, deployment, restart or authentication to DEV. Historical Docker restrictions
do not establish the cause of this particular exit. No Compose/Docker command was
retried during this follow-up. Actual Docker/MySQL/deployed integration remains
unverified, separately from successful H2 and loopback tests.
