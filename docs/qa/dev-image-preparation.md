# DEV Main image preparation without deployment

This path separates image publication from the fresh runtime/backup contract. It does not prepare a server, stop traffic, enable a feature, or authorize installation. Publishing is a separate explicit operator action; adding or merging this code never runs it.

The manual `Deploy dev` workflow now accepts `operation=build-only` with `release_contract=v65-prior-consent`. `deploy` remains the default. Both require `refs/heads/develop` and `approved_sha == github.sha`. The build-only job has no DEV SSH credentials or server command. It builds/pushes Main only, retaining the existing `dev` environment and concurrency group. AI and PROD are outside this path.

The `reviewed_source` JSON has exactly these fields. Values below describe the shape, not evidence to copy into a real run:

```json
{
  "schemaVersion": 1,
  "approvedSha": "<exact 40-character develop SHA>",
  "catalogSha256": "<reviewed V1–V65 catalog SHA256>",
  "ci": {
    "headSha": "<same exact SHA>",
    "mainRunId": 1,
    "aiRunId": 2,
    "reviewedSuccess": true
  },
  "guardianMysql": {
    "sourceSha": "<actual tested catalog source SHA>",
    "catalogSha256": "<same V1–V65 catalog SHA256>",
    "runId": 3,
    "reviewedSuccess": true
  }
}
```

Run IDs and success are operator review statements, not automatically queried CI attestations. Review the actual head/run and catalog equivalence first. The validator requires an exact clean checkout, the reviewed V65 catalog (`83b8357cd5b2ab07f5d01a857323af1107a1718eae9fb1dfcf0daaa5de7467da`), and rejects unknown fields, changed V65 or future V66 migrations. It rejects runtime manifests/host fingerprints in build-only mode so source review cannot be confused with runtime authorization.

On successful publication the workflow records the actual build digest, source SHA, review hash and run ID in its step summary. The kind is `BUILD_ONLY_NOT_DEPLOYMENT_AUTHORIZATION`, with `ciAutomaticallyQueried=false`. The image labels contain the revision, V65 release contract, catalog and deletion-ledger format 1. Use the reviewed immutable digest, not a tag, in a later maintenance plan. Rebuilding a SHA can produce a different digest; it requires review of the new result. This code does not prove that a registry tag is absent or a previously built image is reusable.

For `operation=deploy`, the original complete `reviewed_manifest`, host fingerprint and `dev_main_only.py --prepare` validation are still required before its image build/SSH steps. Source review is not accepted in their place. The [maintenance lifecycle](dev-v65-maintenance.md) remains necessary for intentional Main/Nginx quiescence, fresh backups, isolated restore, files/ledger evidence and held/reopen behavior.

The Dockerfile also packages `/app/deletion-ledger.jar`. The default image entry point is still `/app/app.jar`. The separate operator entry point defaults to `OPERATOR_WRAPPER_REQUIRED_NO_ACTION` without starting the application or connecting to a database; [the operator boundary](deletion-ledger-operator.md) is required for explicit recovery actions.

The extra operator JAR includes its runtime dependencies and increases image storage needs. Measure the actual published image and available DEV space before pulling or creating a restore copy; local JAR size is not the compressed registry-layer size or a capacity approval.

Local verification commands (no image publication):

```text
python3 -I -S -B scripts/deploy/test_dev_image_source.py
python3 -I -S -B scripts/deploy/test_deletion_ledger.py
cd main-service
./gradlew build --offline --no-daemon
cd ..
python3 -I -S -B scripts/deploy/check_deletion_ledger_jar.py
```

Workflow dispatch, registry publication, Linux container execution and real DEV/AI/FE integration are not established by these local tests. This change requires a reviewed new commit and its CI before it can supply a new deployment image; the prior `b6ea8b20…` image cannot contain uncommitted tooling.

Recorded local verification on 2026-11-05: the offline full build ran 2,316 tests, with 2,285 passed, 31 skipped, zero failures/errors. All 11 Java operator tests passed, including export without the prior-consent columns and logical withdrawal/token revocation through the narrow context. Source-only tests passed 9/9; the isolated operator boundary passed 16/16 with fake subprocesses; launch/operations/source-proof metadata passed 204/204. The packaged operator passed two no-action/rejected-execution checks, and the normal Main JAR passed six synthetic no-send checks.

The [new local receipt](../../tools/qa/operations-acceptance-20261007/local-verification/dev-preparation-20261105.receipt.json) and [JUnit metadata](../../tools/qa/operations-acceptance-20261007/local-verification/dev-preparation-20261105.tests.json) are actual unsigned worktree evidence, not CI or live acceptance. Earlier receipts remain unchanged historical evidence. The Main input tree SHA256 is `15a9e780b23a0b4895435104359317012e8fbca56ca17328e6f428cbb14fc54d` (1,235 inputs).
