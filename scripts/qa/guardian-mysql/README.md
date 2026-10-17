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

Both deploy workflows require explicit `workflow_dispatch`; creating this PR
does not dispatch either workflow. This validation does not authorize merge,
deployment, real mail/AI use, or access to any existing database.

Service container lifecycle follows [GitHub's service container documentation](https://docs.github.com/en/actions/tutorials/use-containerized-services/use-docker-service-containers).
