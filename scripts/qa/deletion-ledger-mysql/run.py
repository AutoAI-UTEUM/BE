"""GitHub-hosted synthetic MySQL 8.0.46 drill. No existing containers, live URLs or image publication."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
MYSQL = "mysql:8.0.46"
JAVA = "eclipse-temurin:21-jre"
LABEL = "io.uteum.synthetic-ledger"
V64 = "75af39b82695df2088c3916f6ffc3a2b9c71627fb4cf2fcca3b080cb3335d228"
V65 = "83b8357cd5b2ab07f5d01a857323af1107a1718eae9fb1dfcf0daaa5de7467da"


def require(value, code):
    if not value: raise ValueError(code)


def now(): return dt.datetime.now(dt.timezone.utc).isoformat()
def digest(raw): return hashlib.sha256(raw).hexdigest()


def validate_host(env):
    require(sys.platform.startswith("linux") and env.get("GITHUB_ACTIONS") == "true"
            and env.get("RUNNER_OS") == "Linux" and env.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and env.get("GITHUB_REPOSITORY") == "AutoAI-UTEUM/BE"
            and re.fullmatch(r"[a-f0-9]{40}", env.get("GITHUB_SHA", ""))
            and re.fullmatch(r"[0-9]+", env.get("GITHUB_RUN_ID", "")), "GITHUB_HOSTED_SYNTHETIC_ONLY")
    require(socket.gethostname() != "ip-172-31-40-63.ap-northeast-2.compute.internal", "LIVE_HOST_FORBIDDEN")
    require(not any(env.get(k) for k in ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")), "REMOTE_DOCKER_OVERRIDE_FORBIDDEN")


def prepare_fixture(jar, output, javac="javac", call=subprocess.run):
    """Compile a test-only class against the existing packaged code; no application source edit."""
    output = Path(output).resolve(); output.mkdir(parents=True, exist_ok=True)
    classes = output / "classes"; libs = output / "lib"; fixture = output / "fixture"
    for directory in (classes, libs, fixture): directory.mkdir(mode=0o755, exist_ok=True)
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.infolist():
            if entry.is_dir(): continue
            if entry.filename.startswith("BOOT-INF/classes/"):
                target = classes / entry.filename.removeprefix("BOOT-INF/classes/")
            elif entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar"):
                target = libs / entry.filename.removeprefix("BOOT-INF/lib/")
            else: continue
            require(target.resolve().is_relative_to(output), "JAR_PATH_TRAVERSAL")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry)); target.chmod(0o644)
    target = output / "deletion-ledger.jar"; target.write_bytes(Path(jar).read_bytes()); target.chmod(0o644)
    cp = str(classes) + os.pathsep + str(libs / "*")
    result = call([javac, "-encoding", "UTF-8", "-cp", cp, "-d", str(fixture), str(HERE / "FixtureSetup.java")], capture_output=True, check=False, timeout=120)
    require(result.returncode == 0, "SYNTHETIC_FIXTURE_COMPILE_FAILED")
    if os.name == "posix":
        for path in output.rglob("*"): path.chmod(0o755 if path.is_dir() else 0o644)
    return output


class Drill:
    def __init__(self, artifact, env, call=subprocess.run):
        validate_host(env)
        self.env, self.call, self.artifact = env, call, Path(artifact).resolve()
        self.op = secrets.token_hex(12); self.prefix = "ledger-ci-" + self.op
        self.password = "synthetic-" + secrets.token_hex(16)
        self.ids = {}; self.uuids = {}; self.cases = []; self.phase = "PREPARE"
        self.user = None; self.retain = None

    def command(self, args, data=None, env=None, timeout=60, codes=(0,)):
        result = self.call(args, input=data, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, timeout=timeout)
        if result.returncode not in codes:
            # Only exception class names from the synthetic fixture are safe diagnostic metadata.
            try: types = json.loads(result.stdout).get("exceptionTypes", [])
            except Exception: types = []
            require(False, "SYNTHETIC_COMMAND_FAILED_" + self.phase + "_EXIT_" + str(result.returncode) + ("_" + "_".join(types) if types else ""))
        require(len(result.stdout) <= 32 * 1024 * 1024, "SYNTHETIC_OUTPUT_CAP")
        return result.stdout, result.returncode

    def docker(self, *args, **kw): return self.command(["docker", "--host", "unix:///var/run/docker.sock", *args], **kw)

    def inspect(self, cid):
        fmt = '{"id":{{json .Id}},"name":{{json .Name}},"image":{{json .Config.Image}},"labels":{{json .Config.Labels}},"running":{{.State.Running}},"ports":{{json .HostConfig.PortBindings}},"network":{{json .HostConfig.NetworkMode}},"networks":{{json .NetworkSettings.Networks}},"mounts":{{json .Mounts}},"tmpfs":{{json .HostConfig.Tmpfs}},"health":{{with (index .State "Health")}}{{json .Status}}{{else}}null{{end}}}'
        return json.loads(self.docker("inspect", "--type", "container", "--format", fmt, cid)[0])

    def own(self, item):
        require((item["labels"] or {}).get(LABEL) == self.op and item["name"].startswith("/" + self.prefix + "-"), "CONTAINER_OWNERSHIP_MISMATCH")

    def create_mysql(self, role):
        name = self.prefix + "-" + role
        child = dict(os.environ, MYSQL_DATABASE="edupilot", MYSQL_USER="ledger_synthetic", MYSQL_PASSWORD=self.password, MYSQL_ROOT_PASSWORD="root-" + self.password)
        health = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --user="$MYSQL_USER" --database=edupilot --batch --execute="SELECT 1" >/dev/null'
        raw, _ = self.docker("run", "--detach", "--pull", "never", "--name", name, "--label", LABEL + "=" + self.op,
            "--network", "none", "--memory", "512m", "--cpus", "1", "--tmpfs", "/var/lib/mysql:rw,size=536870912",
            "--health-cmd", health, "--health-interval", "2s", "--health-timeout", "5s", "--health-retries", "45",
            "--env", "MYSQL_DATABASE", "--env", "MYSQL_USER", "--env", "MYSQL_PASSWORD", "--env", "MYSQL_ROOT_PASSWORD",
            MYSQL, "--mysqlx=0", "--performance-schema=OFF", "--skip-log-bin", "--innodb-buffer-pool-size=64M", "--event-scheduler=OFF", env=child)
        cid = raw.decode().strip(); require(re.fullmatch(r"[a-f0-9]{64}", cid), "NEW_CONTAINER_ID_REQUIRED")
        self.ids[role] = cid
        for _ in range(70):
            item = self.inspect(cid); self.own(item)
            require(item["image"] == MYSQL and item["network"] == "none" and set(item["networks"]) == {"none"}
                    and not item["ports"] and "/var/lib/mysql" in item["tmpfs"]
                    and all(m["Type"] == "tmpfs" for m in item["mounts"]), "NEW_TMPFS_ISOLATION_REQUIRED")
            require(item["running"] and item["health"] in ("starting", "healthy"), "MYSQL_HEALTH_FAILED_NO_RETRY")
            if item["health"] == "healthy": break
            time.sleep(2)  # Read expected startup state; never repeat a failed Docker command.
        else: raise TimeoutError("SYNTHETIC_MYSQL_STARTUP_TIMEOUT")
        rows = self.sql(role, "SELECT @@server_uuid,VERSION(),DATABASE(),@@event_scheduler;").decode().strip().split("\t")
        require(len(rows) == 4 and re.fullmatch(r"[a-f0-9-]{36}", rows[0]) and rows[1:] == ["8.0.46", "edupilot", "OFF"], "SYNTHETIC_MYSQL_IDENTITY_REQUIRED")
        self.uuids[role] = rows[0]

    def sql(self, role, query):
        self.own(self.inspect(self.ids[role]))
        shell = 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database=edupilot --batch --raw --skip-column-names'
        return self.docker("exec", "--interactive", self.ids[role], "sh", "-c", shell, data=query.encode())[0]

    def java(self, role, action, fixture=False, raw=None, codes=(0,), extra=None):
        self.own(self.inspect(self.ids[role]))
        values = {"LEDGER_DB_USER": "ledger_synthetic", "LEDGER_DB_PASSWORD": self.password,
            "SYNTHETIC_LEDGER_TEST": "true", "SYNTHETIC_EXPECTED_UUID": self.uuids[role], "SYNTHETIC_USER_ID": str(self.user or ""),
            "SYNTHETIC_RETAIN_UNTIL": self.retain or "", "LEDGER_WRAPPER_VERIFIED": "true", "LEDGER_SOURCE_SHA": self.env["GITHUB_SHA"],
            "LEDGER_LIVE_UUID": self.uuids["source"], "LEDGER_TARGET_UUID": self.uuids[role], "LEDGER_REVIEWED_AT": now(),
            "LEDGER_BACKUP_AT": self.backup_at if hasattr(self, "backup_at") else now(), "LEDGER_INPUT_SHA256": digest(raw) if raw else "", "LEDGER_RESTORE_EPOCH": "ci_restore_1"}
        values.update(extra or {})
        args = ["run", "--rm", "--pull", "never", "--name", self.prefix + "-java", "--label", LABEL + "=" + self.op,
            "--interactive", "--network", "container:" + self.ids[role], "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--user", "10001:10001", "--memory", "1024m", "--cpus", "1", "--pids-limit", "128", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
            "--mount", "type=bind,src=" + str(self.artifact) + ",dst=/artifact,readonly"]
        for key in values: args.extend(["--env", key])
        args.extend([JAVA, "java", "-XX:-UsePerfData", "-Xmx512m"])
        if fixture: args.extend(["-cp", "/artifact/classes:/artifact/lib/*:/artifact/fixture", "io.edupilotops.synthetic.FixtureSetup", action])
        else: args.extend(["-jar", "/artifact/deletion-ledger.jar", action, "--execute"])
        output, code = self.docker(*args, data=raw, env=dict(os.environ, **values), timeout=120, codes=codes)
        return json.loads(output), output, code

    def case(self, name): self.cases.append(name); print("SYNTHETIC_PASS " + name, flush=True)

    def production_cli_negative(self):
        with tempfile.TemporaryDirectory(prefix="ledger-host-negative-", dir=self.env["RUNNER_TEMP"]) as directory:
            path = Path(directory) / "plan.json"
            plan = {"schemaVersion":1,"approvedSha":self.env["GITHUB_SHA"],"operationId":"a"*32,"checkedAtUtc":now(),
                "quiescenceReceiptSha256":"b"*64,"backupCreatedAtUtc":now(),"backupReceiptSha256":"c"*64,
                "backupFile":{"relativePath":"backups/synthetic.sql.gz","sizeBytes":1,"sha256":"d"*64},
                "services":{name:{"id":str(i)*64,"imageId":"sha256:"+str(i+4)*64} for i,name in enumerate(("main-service","ai-service","mysql","nginx"),1)},
                "liveServerUuid":"12345678-1234-1234-1234-123456789abc",
                "operatorImage":{"imageId":"sha256:"+"9"*64,"repoDigest":"ghcr.io/autoai-uteum/main-service@sha256:"+"e"*64},
                "isolated":{"id":"f"*64,"serverUuid":"12345678-1234-1234-1234-123456789abd","dataVolume":"edupilot-restore-"+"a"*32+"-mysql"},
                "restoreEpoch":"synthetic_only","operatorMemoryMb":512,"operatorCpus":1}
            plan["checkedAtUtc"] = now();path.write_text(json.dumps(plan));path.chmod(0o600)
            args = [sys.executable,"-I","-S","-B",str(ROOT/"scripts/deploy/deletion_ledger.py"),"--action","export","--plan",str(path)]
            output,_ = self.command(args)
            require(json.loads(output)["status"] == "LEDGER_PLAN_VALIDATED_NO_ACTION", "SYNTHETIC_NEGATIVE_PLAN_SHAPE")
            output,code = self.command(args+["--execute"],codes=(2,))
            require(code == 2 and json.loads(output)["status"] == "OPERATOR_LEDGER_BLOCKED_OR_OUTCOME_UNKNOWN_NO_RETRY", "PRODUCTION_CLI_MUST_REFUSE_CI_HOST")
        self.case("production_cli_refuses_non_dev_execution_without_loosening_host_gate")

    def run(self):
        self.phase = "PRODUCTION_HOST_GATE_NEGATIVE"
        self.production_cli_negative()
        self.phase = "PUBLIC_IMAGE_PULL"
        for image in (MYSQL, JAVA): self.docker("pull", image, timeout=240)
        self.phase = "CREATE_OWNED_TMPFS_DATABASES"
        for role in ("source", "target"): self.create_mysql(role)
        require(self.uuids["source"] != self.uuids["target"], "DISTINCT_SYNTHETIC_UUIDS_REQUIRED")
        self.case("two_fresh_mysql_8046_tmpfs_databases_without_ports_or_external_network")
        self.phase = "BOOTSTRAP_SOURCE_V64"
        self.java("source", "bootstrap64", fixture=True)
        seeded, _, _ = self.java("source", "seed", fixture=True); self.user = seeded["userId"]
        self.phase = "SYNTHETIC_DUMP_AND_ISOLATED_RESTORE"
        shell = 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --user="$MYSQL_USER" --single-transaction --no-tablespaces --set-gtid-purged=OFF --column-statistics=0 --skip-comments --skip-dump-date edupilot'
        dump = self.docker("exec", self.ids["source"], "sh", "-c", shell)[0]
        require(0 < len(dump) < 16 * 1024 * 1024, "SYNTHETIC_DUMP_CAP")
        self.sql("target", dump.decode()); self.backup_at = now(); del dump
        self.case("synthetic_v64_dump_restored_only_into_new_empty_target")
        self.phase = "SOURCE_TOMBSTONES"
        marked, _, _ = self.java("source", "mark-deletions", fixture=True); self.retain = marked["retainUntil"]
        before, _, _ = self.java("source", "snapshot", fixture=True)
        require(before["catalog"] == V64 and before["v65Columns"] == before["v65Check"] == 0, "ACTUAL_SOURCE_V64_REQUIRED")
        self.phase = "READ_ONLY_EXPORT"
        self.java("source", "readonly-probe", fixture=True)
        doc, ledger, _ = self.java("source", "export")
        require(doc["catalogSha256"] == V64 and doc["sourceUuid"] == self.uuids["source"] and len(doc["snapshots"]) == 4, "ACTUAL_V64_EXPORT_REQUIRED")
        fields = {"kind","resourceKey","sourceMaterialKey","sourceUserId","accountCreatedAt","originalEmailHash","requestedAt","retainUntil"}
        require(all(set(row) == fields for row in doc["snapshots"]), "ALL_EIGHT_SNAPSHOT_FIELDS_REQUIRED")
        require(all(row["retainUntil"] is None for row in doc["snapshots"] if row["kind"] != "EXTERNAL_AI"), "UNKNOWN_RETENTION_MUST_REMAIN_UNKNOWN")
        after, _, _ = self.java("source", "snapshot", fixture=True); require(before == after, "EXPORT_CHANGED_SOURCE")
        self.case("actual_v64_export_read_only_with_missing_v65_columns_and_all_eight_fields")
        self.phase = "ISOLATED_V65_MIGRATION"
        migrated, _, _ = self.java("target", "migrate-isolated"); require(migrated["status"] == "ISOLATED_SCHEMA_V65", "V65_MIGRATION_RESULT")
        snap, _, _ = self.java("target", "snapshot", fixture=True)
        require(snap["catalog"] == V65 and snap["v65Columns"] == 3 and snap["v65Check"] == 1, "ACTUAL_V65_SCHEMA_REQUIRED")
        self.case("isolated_v64_to_v65_flyway_checksums_columns_and_enforced_constraint")
        self.phase = "LOGICAL_REPLAY"
        replay, _, _ = self.java("target", "replay", raw=ledger)
        require(replay["status"] == "ISOLATED_LOGICAL_REPLAY_COMPLETE" and replay["results"]["APPLIED"] == 1
                and replay["results"]["ALREADY_APPLIED"] == 1 and not replay["physicalDeletionExecuted"], "ACTUAL_LOGICAL_REPLAY_REQUIRED")
        self.java("target", "verify-restored", fixture=True)
        self.case("account_material_token_revocation_retention_and_provider_free_context")
        self.phase = "IDEMPOTENT_REPLAY"
        pre, _, _ = self.java("target", "snapshot", fixture=True)
        again, _, _ = self.java("target", "replay", raw=ledger)
        post, _, _ = self.java("target", "snapshot", fixture=True)
        require(pre == post and again["results"]["ALREADY_APPLIED"] == 2, "SAME_EPOCH_MUST_BE_IDEMPOTENT")
        self.case("same_epoch_does_not_reopen_or_duplicate_tombstones")
        self.phase = "HASH_AND_UUID_NEGATIVES"
        self.java("target", "replay", raw=ledger, codes=(2,), extra={"LEDGER_INPUT_SHA256": "0" * 64})
        self.java("source", "migrate-isolated", codes=(2,))
        wrong="00000000-0000-4000-8000-000000000000"
        self.java("source", "export", codes=(2,), extra={"LEDGER_LIVE_UUID":wrong,"LEDGER_TARGET_UUID":wrong})
        require(self.java("target", "snapshot", fixture=True)[0] == post, "REJECTED_INPUT_CHANGED_TARGET")
        require(self.java("source", "snapshot", fixture=True)[0] == before, "SOURCE_WRITE_OR_MIGRATION_OCCURRED")
        self.case("bad_digest_and_same_source_target_uuid_rejected_without_writes")
        self.phase = "IDENTITY_MISMATCH"
        self.java("target", "prepare-mismatch", fixture=True)
        held, _, code = self.java("target", "replay", raw=ledger, codes=(3,), extra={"LEDGER_RESTORE_EPOCH": "ci_restore_identity"})
        require(code == 3 and held["status"] == "IDENTITY_MISMATCH_HOLD", "IDENTITY_MISMATCH_MUST_HOLD")
        self.java("target", "verify-held", fixture=True)
        self.case("identity_mismatch_preserves_replacement_account_and_holds")
        self.phase = "EXPORT_PAGINATION"
        self.java("source", "add-pagination", fixture=True)
        paged, _, _ = self.java("source", "export")
        require(len(paged["snapshots"]) == 1005, "EXPORT_MUST_CROSS_1000_ROW_CURSOR")
        self.case("real_mysql_export_pagination_beyond_one_thousand")
        return {"passedCases": self.cases, "mysqlVersion": "8.0.46", "sourceAndTargetUuidsDistinct": True, "sourceCatalog": V64, "targetCatalog": V65}

    def cleanup(self):
        self.phase = "CLEANUP_OWNED_ONLY"
        rows = self.docker("ps", "--all", "--quiet", "--no-trunc", "--filter", "label=" + LABEL + "=" + self.op)[0].decode().split()
        for cid in rows:
            require(re.fullmatch(r"[a-f0-9]{64}", cid), "CLEANUP_ID_REQUIRED")
            self.own(self.inspect(cid)); self.docker("rm", "--force", "--volumes", cid)
        return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true"); parser.add_argument("--compile-only", action="store_true")
    parser.add_argument("--jar", type=Path, default=ROOT / "main-service/build/operator/deletion-ledger.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "main-service/build/ledger-synthetic-result.json")
    parser.add_argument("--javac", default="javac"); args = parser.parse_args()
    if not args.execute and not args.compile_only:
        print(json.dumps({"status":"SYNTHETIC_PLAN_NO_ACTION","dockerCalls":0})); return 0
    parent = ROOT / "main-service/build/ledger-synthetic-compile" if args.compile_only else Path(os.environ.get("RUNNER_TEMP", "/nonexistent"))
    if not args.compile_only: validate_host(os.environ)
    parent.mkdir(parents=True, exist_ok=True)
    drill = None; summary = {"kind":"SYNTHETIC_MYSQL_OPERATOR_TEST_NOT_RELEASE_EVIDENCE","liveDataUsed":False,"releaseAuthorization":False,"imagePublication":False}
    failure = None
    try:
        with tempfile.TemporaryDirectory(prefix="ledger-synthetic-", dir=parent) as temp:
            # Only public code artifacts enter this readable bind; ledger/dump bytes stay on private pipes.
            artifact = prepare_fixture(args.jar, Path(temp) / "artifact", args.javac)
            if args.compile_only:
                print(json.dumps({"status":"SYNTHETIC_FIXTURE_COMPILED_NO_DOCKER","dockerCalls":0})); return 0
            Path(temp).chmod(0o755)
            drill = Drill(artifact, dict(os.environ)); summary["sourceSha"] = os.environ["GITHUB_SHA"]
            try: summary.update(drill.run())
            except Exception as error: failure = error; summary.update(failedPhase=drill.phase, failureType=type(error).__name__, reason=str(error) if isinstance(error,ValueError) and re.fullmatch(r"[A-Za-z0-9_]+",str(error)) else "SYNTHETIC_EXECUTION_FAILED_NO_RETRY")
            finally:
                try: summary["cleanupResult"] = drill.cleanup()
                except Exception: summary["cleanupResult"] = 1; failure = failure or RuntimeError("OWNED_CLEANUP_FAILED")
    except Exception as error:
        failure = error; summary.update(failureType=type(error).__name__, failedPhase="PREPARE", reason="SYNTHETIC_PREPARE_FAILED_NO_RETRY")
    summary["status"] = "FAILED_NO_RETRY" if failure else "PASSED_SYNTHETIC_ONLY"
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(summary,indent=2)+"\n")
    print(json.dumps(summary,sort_keys=True)); return 1 if failure else 0


if __name__ == "__main__": raise SystemExit(main())
