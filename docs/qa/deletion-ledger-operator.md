# Private deletion ledger operator: export and isolated recovery

This is a local Linux operator boundary, not an HTTP/admin API or an acceptance receipt generator. It exports the current trusted deletion journal from a quiesced DEV database and replays it on a separately restored, isolated copy only. It never creates a backup, provisions resources, restores a SQL dump, pulls/publishes an image, executes physical file/provider deletion, reopens access, or modifies the live schema. No operation in this document was executed on DEV during local implementation.

Before execution, separately authorize and prepare the consistent backup, private files snapshot, isolated MySQL instance/volume, resource/cost budget, retention/cleanup ownership and recovery validation. Do not restore over the current DEV database or use the PROD backup/restore scripts. Keep Main and Nginx deliberately stopped through the existing [maintenance lifecycle](dev-v65-maintenance.md). AI/MySQL must retain their reviewed identities and healthy state. An active `QUIESCED` maintenance journal for this operation must remain current; install/reopen cannot run concurrently because the wrapper takes the same `action.lock`.

The wrapper is `scripts/deploy/deletion_ledger.py`. Default invocation validates a private plan only and requests zero server actions:

```text
python3 -I -S -B scripts/deploy/deletion_ledger.py --action export --plan /private/path/ledger-plan.json
```

Execution additionally requires `--execute --ledger /private/path/trusted-ledger.json --state-dir /private/path/ledger-state`, Python 3.9 or later, an exact clean checkout at `approvedSha`, Linux user `ec2-user`, and the exact previously confirmed DEV hostname `ip-172-31-40-63.ap-northeast-2.compute.internal`. Verify the actual installed Python/Docker versions before execution. The wrapper performs no SSH/authentication or host-key enrollment. Keep existing verified SSH host-key checks and user-entered key passphrases outside the tool. These are operator-side prerequisites, not new credentials or permissions.

Plan schema 1 contains exactly:

| Field | Required meaning |
|---|---|
| `approvedSha` | Exact 40-hex reviewed source/image SHA |
| `operationId` | Existing 32-hex active maintenance session ID |
| `checkedAtUtc` | Fresh actual action review, at most 30 minutes old |
| `quiescenceReceiptSha256` | Actual latest hash in the active maintenance journal, phase `QUIESCED` |
| `backupCreatedAtUtc`, `backupReceiptSha256` | Actual post-quiescence consistent backup completion time and privately retained review evidence hash |
| `backupFile` | `relativePath` under `/opt/edupilot/backups/` ending `.sql.gz`, actual `sizeBytes` and `sha256` |
| `services` | `main-service`, `ai-service`, `mysql`, `nginx`, each with full container `id` and `imageId` |
| `liveServerUuid` | Actual live MySQL server UUID |
| `operatorImage` | Cached exact `imageId` and Main `repoDigest` with reviewed source/catalog/format labels |
| `isolated` | Distinct full container `id`, distinct `serverUuid`, `dataVolume=edupilot-restore-<operationId>-mysql` |
| `restoreEpoch` | Reviewed stable replay epoch, 1–100 ASCII letters/digits/underscore/hyphen |
| `operatorMemoryMb`, `operatorCpus` | Reviewed operator job limits, 256–2048 MiB and greater than 0/up to 2 CPUs |

`schemaVersion` is integer 1. No credentials, child records or ledger entries belong in the plan. A hash is a link to actual retained evidence; it is not proof of consistency by itself. The wrapper validates the existing backup bytes, size and gzip integrity but cannot independently prove when/how the backup was made or that the isolated copy was restored from it. The operator must establish that provenance; missing proof blocks deployment. The existing full install manifest and files/restore evidence guards remain in place.

The wrapper checks four exact live container identities, intended graceful Main/Nginx stop receipt timestamps and exit 0/143, and preserved AI/MySQL healthy state. It validates the actual private maintenance hash chain, active session and fresh baseline/QUIESCED time. The backup must follow quiescence, precede the action review and remain within 30 minutes. Do not refresh old evidence timestamps to extend this window.

The isolated DB must use the live baseline MySQL image, be running on `network=none` with only the actual `none` network attached and no published ports, and have exactly the distinct named `/var/lib/mysql` volume. Both the container and the volume require labels `com.uteum.restore.scope=isolated-dev`, `com.uteum.restore.operation=<operationId>`. The container must not be in Compose project `edupilot`. The volume must use the `local` driver without driver options and cannot be a live MySQL mount; bind aliases and external volume drivers are rejected. The actual server UUID must differ from live. MySQL must report `8.0.46`, database `edupilot`, and isolated `@@event_scheduler=OFF`. Provisioning it and safely importing the backup are separate reviewed operations. A copied MySQL data directory preserving the live UUID will be rejected.

Run these actions separately, in order, using the same plan binding and private state directory:

1. **`export`** reads the live deletion journal in one read-only repeatable-read transaction, including pagination beyond 1,000 entries. It creates a new private ledger file and records its actual hash/count. The source Flyway catalog must be exactly reviewed V1–V64 or V1–V65 with all migrations successful. It performs no journal writes.
2. **`migrate-isolated`** requires the completed export receipt and unchanged ledger file. It explicitly runs reviewed Flyway V1–V65 migrations on the UUID-verified isolated copy only, permitting an already-V65 copy. The wrapper then reads the actual three V65 column definitions and enforced check constraint using the existing reviewed deployment validator before recording completion. No baseline, repair, ignore, clean, out-of-order or down migration is offered. This is necessary because account withdrawal cleanup now uses V65 columns. It is not a live DB migration.
3. **`replay`** also requires the completed isolated-schema receipt and rechecks the actual V65 columns and constraint. It validates the entire private ledger before applying batches of up to 1,000 through the existing `DeletionRestoreService`. Logical account/material withdrawal, all seven withdrawal hooks and token revocation use the same shared cleanup code as the application. Unknown retention remains unknown and exported retention deadlines are preserved. Identity mismatch produces a hold/failure, never completion or reopen authorization.

Only `checkedAtUtc` can be refreshed after a new actual review; backup/quiescence/source/identity/epoch fields cannot silently change within the operation. In particular, the replay review must be after the actual export time. Keep the operator state directory separate from the maintenance journal as well as outside the source checkout. The default 16 MiB/100,000-entry format cap fails closed rather than truncating; a larger ledger requires a separate reviewed change.

Ledger format 1 is private JSON with header `kind=EDUPILOT_TRUSTED_DELETION_LEDGER_V1`, `sourceSha`, `sourceUuid`, `catalogSha256`, `exportedAtUtc` and `snapshots`. Each snapshot preserves exactly the existing eight fields: `kind`, `resourceKey`, `sourceMaterialKey`, `sourceUserId`, `accountCreatedAt`, `originalEmailHash`, `requestedAt`, `retainUntil`. Null values are retained, times use exact microsecond precision, duplicate JSON keys/resources, unknown fields, invalid account identity and changed hashes are rejected. The ledger contains restricted linkable identifiers even without raw email. Keep it outside source control in canonical private directories (0700) and files (0600); never post it to chat, CI artifacts or cloud summaries.

The job runs cached reviewed `/app/deletion-ledger.jar` as UID/GID 10001 with a read-only root filesystem, no capabilities, no-new-privileges, bounded memory/CPU/PIDs, and temporary `/tmp` only. Export joins the live DB network namespace solely for read-only SQL; migration/replay joins the verified network-isolated DB namespace. It imports existing application DB credentials into process memory and forwards environment **names**, not values, in Docker argv. No password, full environment, arbitrary SQL error or ledger contents appear in receipts/normal output. The explicit database-only Spring context has no web server, application configuration scan, schedulers, mail or AI clients. The normal image entry point remains unchanged.

Every requested action writes an exclusive durable `INTENT` before launching the child, and a `COMPLETE` receipt only from its actual successful result. A private session index under `.dev-maintenance/` binds the state directory and plan, so selecting another state directory cannot reset consumed actions. Timeout, failed child, partial replay or unexpected protocol leaves the action unresolved. **Do not automatically retry, delete the intent/index, change state directories or reopen.** Inspect/reconcile the outcome and retain the isolated copy. A completed logical replay is still not proof of file recovery, full schema/SQL restore fidelity, or end-to-end service recovery. Collect those actual results separately for the install manifest.

Local tests use synthetic data, fake Docker subprocesses and H2. They do not prove Linux namespace isolation, real MySQL/Flyway behavior, restored files, a current DEV backup, image publication or production readiness. Keep the existing Main worker-start and V65 rollback boundaries from the maintenance runbook: after new Main startup, workers may already have written; an old image is not an automatic safe rollback.
