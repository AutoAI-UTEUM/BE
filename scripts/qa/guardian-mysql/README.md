# Isolated guardian MySQL verification

`guardian-mysql-ci.yml` uses a standard GitHub-hosted Ubuntu runner, Java 21 and
`mysql:8.4.11`. It requires no repository secrets, cloud credentials, deployment
environment or persistent database volume. The public fixture password belongs
only to the job's disposable service. The service datadir is tmpfs and has no host
port. GitHub removes the service after the job; the script removes its test
container and internal network on exit.

Dependencies and test classes are prepared online without running tests. The test
JVM then runs Gradle offline in a container attached only to a Docker internal
network with the MySQL service. The service's original network is disconnected.
The test container has no Docker socket, host networking, Git checkout token,
host home directory or inherited application credentials.

All first JDBC calls and both Flyway phases use `IsolatedMysql`: the hostname is
always `guardian-mysql`, port 3306, and only four literal synthetic schema names
are accepted. Arbitrary JDBC URLs and connection options are not accepted.
`GUARDIAN_MYSQL_ISOLATED=true`, `GITHUB_ACTIONS=true` and the service UUID obtained
by `docker exec` are mandatory before the first connection. Database name,
datadir `/var/lib/mysql/`, server port, UUID and version are checked before any
fixture migration/reset. The network is the pre-connection isolation boundary;
the identity queries are additional checks. Normal CI leaves the opt-in unset.

The schemas are created once by `docker exec` on the verified job service:

| Suite | Schema | MySQL invocations |
| --- | --- | ---: |
| GuardianConsentFenceMysqlTest | guardian_consent_fence_mysql_synthetic | 14 |
| GuardianAdminLockMysqlTest | guardian_admin_lock_jpa_synthetic | 22 |
| GuardianTeamFullMigrationTest | guardian_team_full_migration_synthetic | 1 |
| TurnPersistenceConsentMysqlTest | guardian_turn_persistence_mysql_synthetic | 2 |

The FullMigration H2 case is counted separately (1). Parameter sources are
unchanged from develop `d4b8c85cff81af4aee870efa0f9803189ad79c77`.
FullMigration exercises canonical V1–V60, inserts synthetic legacy evidence,
then migrates through V64. The turn persistence fixture also expects V64.
Mail is disabled and AI clients are mocked; the test network has no internet route.

`report.py` rejects missing suites/cases, duplicate cases and any failure, error,
skip or abort. It prints per-suite counts, every case name, a separate H2 count,
and the exact original JUnit XML as zlib/base64 with SHA-256 in Actions logs.
An aborted case encoded by Gradle as an untyped `<skipped>` is conservatively
counted as skipped; either status fails verification. Zero skipped means zero
aborted in a successful result. No cache or artifact storage is added by this
workflow. Existing Main/AI CI still execute all their checks; only cache writes
and report uploads are disabled for the dedicated verification PR branch
`feature/guardian-mysql-isolated-20261017` to avoid unverified storage costs.

The workflow is triggered by pull requests targeting either `develop` or `main`
when the workflow, its scripts or `main-service` changes. The MySQL job runs only
when the PR head repository matches this repository; fork PRs skip the job. A release PR
from `develop` to `main` therefore verifies the integrated PR revision with the
same four MySQL suites and separate H2 case. A successful run on an earlier
`develop` revision does not replace that release PR's result. Inspect its actual
`GUARDIAN_JUNIT_SUMMARY` and cleanup result before considering this check complete.
The job does not run on push and does not dispatch a deployment.

`python3 -I -S -B scripts/qa/guardian-mysql/test_workflow.py` checks the trigger
matrix and preserved source guards offline. Main CI includes it for PR runs without
a path condition; it executes after preceding steps succeed and is not `always()`.
It does not execute GitHub events or replace the actual MySQL release PR run.

Both deploy workflows require explicit `workflow_dispatch`; creating this PR
does not dispatch either workflow. This validation does not authorize merge,
deployment, real mail/AI use, or access to any existing database.

Service container lifecycle follows [GitHub's service container documentation](https://docs.github.com/en/actions/tutorials/use-containerized-services/use-docker-service-containers).
