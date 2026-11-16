# Synthetic MySQL 8.0.46 deletion-operator validation

This test creates two fresh MySQL 8.0.46 containers on a GitHub-hosted Linux runner. It accepts no existing database/container/URL, uses no DEV/PROD environment or SSH secret, and publishes no image. Data consists only of an `example.invalid` account, a synthetic material/session/token and synthetic deletion records. It is not a release receipt or live recovery drill.

The PR-only workflow is `.github/workflows/deletion-ledger-mysql-ci.yml`. It has `contents: read`, no environment secrets, a same-repository head guard and a 20-minute limit. It builds the existing operator JAR and compiles the test-only `FixtureSetup.java` outside the application source set. This leaves application code, migrations and its local proof input tree unchanged.

`run.py` defaults to zero actions. `--execute` requires the exact repository, a GitHub-hosted Linux runner and normal GitHub run/SHA metadata. It rejects Docker endpoint overrides and the real DEV hostname. Docker uses only the runner's local Unix socket. Each new database uses `network=none`, no published ports, a tmpfs datadir, event scheduler OFF, and bounded CPU/memory. Java joins only the network namespace of the database created by this run, with a read-only filesystem, UID 10001, no capabilities, resource limits, and a read-only public-code artifact bind. No ledger/dump or Docker socket is mounted into it.

The drill checks:

1. The production Python CLI still refuses execution on the non-DEV CI host; no production host/receipt/image gate is relaxed.
2. Fresh source and target UUIDs differ, both report exactly MySQL 8.0.46 and use only owned tmpfs storage.
3. Actual Flyway V1–V64 migration and synthetic seed; a synthetic dump is restored only into the fresh empty target.
4. The actual JAR exports a read-only V64 ledger despite absent V65 columns. The server session is read-only and a write is rejected; source metadata stays unchanged.
5. Actual isolated V65 migration produces the exact expected checksum catalog, three columns and enforced constraint. The source stays V64.
6. Actual JAR replay reapplies account/material withdrawal, revokes tokens, retains the exported deadline, preserves unknown retention and loads the seven hooks without mail/AI clients or schedulers. No physical deletion runs.
7. Repeating the same epoch does not duplicate/reopen intents. Bad digest, same source/target UUID and incorrect actual server UUID fail closed. A replacement account is preserved on identity mismatch, with the expected hold exit.
8. A separate export of 1,005 synthetic records verifies real MySQL pagination beyond the 1,000-row boundary. The large dataset is not presented as a measured large-scale replay benchmark.

Production Python wrapper positives (the full Main/AI/MySQL/Nginx maintenance path, cached release-image provenance and real backup/files receipts) remain outside this harness. The JAR entry point is invoked only after the CI harness verifies its newly created database identities; no synthetic result may substitute for actual DEV readiness. Existing fake-Docker wrapper tests continue to cover the production wrapper's contracts separately.

Every created container has a random run label and name prefix. Cleanup discovers only that label, verifies ownership again, and removes only those exact container IDs and their test volumes. Failure/timeout is not automatically retried. Cleanup failure also fails the job. Only a safe JSON summary of source SHA, catalogs, assertion names, counters and cleanup result is uploaded; raw synthetic dump/ledger bytes stay on private process pipes.

Local no-Docker checks:

```text
python3 -I -S -B scripts/qa/deletion-ledger-mysql/test_run.py
python3 -I -S -B scripts/qa/deletion-ledger-mysql/run.py --compile-only --jar main-service/build/operator/deletion-ledger.jar
```

Local Docker remained inaccessible after the explicitly approved engine recheck (`ACCESS_DENIED`). This harness does not retry that path, start a service, change permissions or configure a remote engine. Actual MySQL recovery results must come from the dedicated CI run, not the local compile or guard tests.
