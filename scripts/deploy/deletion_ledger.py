"""One-shot DEV operator boundary for private ledger export and ISOLATED recovery only.

Default is plan validation. No SSH, image pull/publish, backup, resource provisioning or live restore.
"""
import argparse
import datetime as dt
import hashlib
import gzip
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys

_spec = importlib.util.spec_from_file_location("dev_main_only", Path(__file__).with_name("dev_main_only.py"))
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)
_maintenance_spec = importlib.util.spec_from_file_location("dev_maintenance", Path(__file__).with_name("dev_maintenance.py"))
maintenance = importlib.util.module_from_spec(_maintenance_spec)
_maintenance_spec.loader.exec_module(maintenance)
SOURCE = Path(__file__).resolve().parents[2]
DEPLOY_ROOT = Path("/opt/edupilot")
HOST = "ip-172-31-40-63.ap-northeast-2.compute.internal"
MAX_BYTES = 16 * 1024 * 1024
UUID = r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"


def require(value, code):
    base.require(value, code)


def validate_plan(p):
    base.shape(p, ("schemaVersion", "approvedSha", "operationId", "checkedAtUtc", "quiescenceReceiptSha256",
                   "backupCreatedAtUtc", "backupReceiptSha256", "backupFile", "services", "liveServerUuid", "operatorImage",
                   "isolated", "restoreEpoch", "operatorMemoryMb", "operatorCpus"))
    require(type(p["schemaVersion"]) is int and p["schemaVersion"] == 1, "LEDGER_PLAN_VERSION_REQUIRED")
    require(base.is_hex(p["approvedSha"], r"[a-f0-9]{40}") and base.is_hex(p["operationId"], r"[a-f0-9]{32}"), "EXACT_SOURCE_AND_OPERATION_REQUIRED")
    base.fresh(p["checkedAtUtc"])
    base.fresh(p["backupCreatedAtUtc"])
    require(maintenance.timestamp(p["backupCreatedAtUtc"]) <= maintenance.timestamp(p["checkedAtUtc"]), "BACKUP_PRECEDES_REVIEW")
    require(all(base.is_hex(p[k]) for k in ("quiescenceReceiptSha256", "backupReceiptSha256")), "ACTUAL_QUIESCENCE_BACKUP_RECEIPTS_REQUIRED")
    backup = p["backupFile"]
    base.shape(backup, ("relativePath", "sizeBytes", "sha256"))
    require(isinstance(backup["relativePath"], str) and re.fullmatch(r"backups/[A-Za-z0-9][A-Za-z0-9_.-]{0,150}\.sql\.gz", backup["relativePath"])
            and type(backup["sizeBytes"]) is int and backup["sizeBytes"] > 0 and base.is_hex(backup["sha256"]), "EXACT_PRIVATE_BACKUP_REQUIRED")
    base.shape(p["services"], base.SERVICES)
    ids = set()
    for service in p["services"].values():
        base.shape(service, ("id", "imageId"))
        require(base.is_hex(service["id"]) and re.fullmatch(r"sha256:[a-f0-9]{64}", service["imageId"]), "BASELINE_IDENTITIES_REQUIRED")
        ids.add(service["id"])
    require(len(ids) == 4 and re.fullmatch(UUID, p["liveServerUuid"]), "DISTINCT_BASELINE_REQUIRED")
    image = p["operatorImage"]
    base.shape(image, ("imageId", "repoDigest"))
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", image["imageId"]), "OPERATOR_IMAGE_REQUIRED")
    base.repo_digest("main-service", image["repoDigest"])
    target = p["isolated"]
    base.shape(target, ("id", "serverUuid", "dataVolume"))
    require(base.is_hex(target["id"]) and target["id"] not in ids and re.fullmatch(UUID, target["serverUuid"])
            and target["serverUuid"] != p["liveServerUuid"]
            and target["dataVolume"] == "edupilot-restore-" + p["operationId"] + "-mysql", "ISOLATED_TARGET_REQUIRED")
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,100}", p["restoreEpoch"]), "RESTORE_EPOCH_REQUIRED")
    require(type(p["operatorMemoryMb"]) is int and 256 <= p["operatorMemoryMb"] <= 2048
            and type(p["operatorCpus"]) in (int, float) and 0 < p["operatorCpus"] <= 2, "REVIEWED_RESOURCE_CAP_REQUIRED")
    return p


def private_path(path, must_exist=True):
    path = Path(path)
    require(path.is_absolute() and path.resolve() == path and not path.is_symlink()
            and not path.is_relative_to(SOURCE), "PRIVATE_CANONICAL_PATH_OUTSIDE_SOURCE_REQUIRED")
    parent = path.parent
    require(parent.is_dir() and not parent.is_symlink() and parent.resolve() == parent
            and (os.name != "posix" or parent.stat().st_mode & 0o077 == 0), "PRIVATE_PARENT_REQUIRED")
    if must_exist:
        require(path.is_file() and (os.name != "posix" or path.stat().st_mode & 0o077 == 0), "PRIVATE_FILE_REQUIRED")
    else:
        require(not path.exists(), "NEW_PRIVATE_OUTPUT_REQUIRED")
    return path


def write_new(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600), "wb") as stream:
        stream.write(value); stream.flush(); os.fsync(stream.fileno())
    sync_directory(path.parent)


def sync_directory(path):
    if os.name == "posix":
        descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)


class Operator:
    def __init__(self, plan, state_dir, run=subprocess.run):
        self.p = validate_plan(plan)
        self.state = Path(state_dir)
        self.run = run

    def command(self, args, data=None, env=None, timeout=30):
        result = self.run(args, input=data, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False)
        require(result.returncode == 0 and len(result.stdout) <= MAX_BYTES, "OPERATOR_COMMAND_FAILED_NO_RETRY")
        return result.stdout

    def docker(self, *args, **kwargs):
        return self.command(["docker", *args], **kwargs)

    def inspect(self, cid):
        fmt = ('{"id":{{json .Id}},"imageId":{{json .Image}},"running":{{.State.Running}},'
               '"paused":{{.State.Paused}},"restarting":{{.State.Restarting}},"oom":{{.State.OOMKilled}},'
               '"exitCode":{{.State.ExitCode}},"finishedAt":{{json .State.FinishedAt}},"status":{{json .State.Status}},'
               '"startedAt":{{json .State.StartedAt}},"restartCount":{{.RestartCount}},'
               '"restartPolicy":{{json .HostConfig.RestartPolicy.Name}},"stopSignal":{{json (index .Config "StopSignal")}},'
               '"health":{{with (index .State "Health")}}{{json .Status}}{{else}}null{{end}},"ports":{{json .HostConfig.PortBindings}},'
               '"networkMode":{{json .HostConfig.NetworkMode}},"networks":{{json .NetworkSettings.Networks}},'
               '"mounts":{{json .Mounts}},"labels":{{json .Config.Labels}}}')
        return json.loads(self.docker("inspect", "--type", "container", "--format", fmt, cid))

    def sql_identity(self, cid):
        shell = 'MYSQL_PWD="${MYSQL_PASSWORD:?}" exec mysql --user="${MYSQL_USER:?}" --database="${MYSQL_DATABASE:?}" --batch --raw --skip-column-names --connect-timeout=5'
        statement = b"SET SESSION TRANSACTION READ ONLY; START TRANSACTION READ ONLY; SELECT @@server_uuid, VERSION(), DATABASE(), @@event_scheduler; ROLLBACK;"
        fields = self.docker("exec", "-i", cid, "sh", "-c", shell, data=statement).decode().strip().split("\t")
        require(len(fields) == 4 and re.fullmatch(UUID, fields[0]) and fields[1:3] == ["8.0.46", "edupilot"], "EXPECTED_MYSQL_IDENTITY_REQUIRED")
        return fields[0], fields[3]

    def readonly_sql(self, cid, statement):
        require(statement.startswith("SELECT "), "FIXED_METADATA_QUERY_REQUIRED")
        shell = 'MYSQL_PWD="${MYSQL_PASSWORD:?}" exec mysql --user="${MYSQL_USER:?}" --database="${MYSQL_DATABASE:?}" --batch --raw --skip-column-names --connect-timeout=5'
        data = ("SET SESSION TRANSACTION READ ONLY; START TRANSACTION READ ONLY; " + statement + " ROLLBACK;").encode()
        return self.docker("exec", "-i", cid, "sh", "-c", shell, data=data).decode().splitlines()

    def verify_isolated_schema(self):
        # Reuse the reviewed deployment checks for actual columns AND the enforced constraint.
        base.V65Deployment.verify_v65_columns(self, self.p["isolated"]["id"], True)

    def quiescence(self):
        journal = maintenance.Journal(DEPLOY_ROOT, self.p["operationId"])
        journal.require_active()
        events = journal.read()
        require(events[0]["phase"] == "BASELINE" and events[-1]["phase"] == "QUIESCED"
                and events[-1]["sha256"] == self.p["quiescenceReceiptSha256"], "ACTUAL_ACTIVE_QUIESCENCE_REQUIRED")
        base.fresh(events[0]["atUtc"])
        base.fresh(events[-1]["atUtc"])
        require(maintenance.timestamp(events[-1]["atUtc"]) <= maintenance.timestamp(self.p["backupCreatedAtUtc"]), "BACKUP_MUST_FOLLOW_ACTUAL_QUIESCENCE")
        baseline = events[0]["data"]["snapshot"]
        require(events[0]["data"]["targetImageId"] == self.p["operatorImage"]["imageId"], "MAINTENANCE_TARGET_IMAGE_CHANGED")
        for name, expected in self.p["services"].items():
            require(all(baseline[name][k] == expected[k] for k in ("id", "imageId")), "MAINTENANCE_BASELINE_CHANGED")
        return events

    def verify_backup(self):
        backup = self.p["backupFile"]
        path = private_path(DEPLOY_ROOT / backup["relativePath"])
        require(path.stat().st_size == backup["sizeBytes"], "BACKUP_SIZE_CHANGED")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            initial = os.fstat(stream.fileno())
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
            require(digest.hexdigest() == backup["sha256"], "BACKUP_HASH_CHANGED")
            stream.seek(0)
            count = 0
            with gzip.GzipFile(fileobj=stream) as archive:
                for chunk in iter(lambda: archive.read(1024 * 1024), b""): count += len(chunk)
            signature = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns)
            require(count > 0 and signature(initial) == signature(os.fstat(stream.fileno())) == signature(path.stat()), "BACKUP_EMPTY_OR_CHANGED")

    def verify(self):
        events = self.quiescence()
        self.verify_backup()
        require(base.sha_json(base.source_catalog_v65(SOURCE)) == base.V65_CATALOG_SHA256, "SOURCE_CATALOG_DRIFT")
        for name, expected in self.p["services"].items():
            ids = self.docker("ps", "--all", "--quiet", "--no-trunc", "--filter", "label=com.docker.compose.project=edupilot", "--filter", "label=com.docker.compose.service=" + name).decode().split()
            require(ids == [expected["id"]], "LIVE_CONTAINER_CHANGED")
            item = self.inspect(expected["id"])
            baseline = events[0]["data"]["snapshot"][name]
            require(item["id"] == expected["id"] and item["imageId"] == expected["imageId"]
                    and not item["paused"] and not item["restarting"] and not item["oom"]
                    and all(item[k] == baseline[k] for k in ("ports", "networkMode", "restartPolicy", "stopSignal", "startedAt", "restartCount")), "LIVE_IDENTITY_OR_STATE_DRIFT")
            if name in ("main-service", "nginx"):
                stopped = next(e for e in events if e["phase"] == ("MAIN_STOPPED" if name == "main-service" else "INGRESS_STOPPED"))
                require(not item["running"] and item["status"] == "exited" and type(item["exitCode"]) is int and item["exitCode"] in (0, 143)
                        and item["finishedAt"] == stopped["data"]["finishedAtUtc"], "ACTUAL_MAIN_AND_INGRESS_QUIESCENCE_REQUIRED")
            else:
                require(item["running"] and item["health"] == "healthy", "PRESERVED_SERVICE_NOT_HEALTHY")
        live = self.p["services"]["mysql"]
        require(self.sql_identity(live["id"])[0] == self.p["liveServerUuid"], "LIVE_DATABASE_CHANGED")
        target = self.p["isolated"]
        item = self.inspect(target["id"])
        labels = item["labels"] or {}
        require(item["id"] == target["id"] and item["imageId"] == live["imageId"] and item["running"]
                and not item["paused"] and not item["restarting"] and not item["oom"]
                and item["networkMode"] == "none" and set(item["networks"] or {}) == {"none"} and not item["ports"]
                and labels.get("com.uteum.restore.scope") == "isolated-dev"
                and labels.get("com.uteum.restore.operation") == self.p["operationId"]
                and labels.get("com.docker.compose.project") != "edupilot", "ISOLATION_PROOF_REJECTED")
        require(len(item["mounts"]) == 1 and item["mounts"][0]["Type"] == "volume"
                and item["mounts"][0]["Name"] == target["dataVolume"]
                and item["mounts"][0]["Destination"] == "/var/lib/mysql", "FRESH_DRILL_VOLUME_REQUIRED")
        live_mounts = self.inspect(live["id"])["mounts"]
        require(target["dataVolume"] not in {m.get("Name") for m in live_mounts}, "LIVE_DATA_VOLUME_ALIAS_REJECTED")
        volume = json.loads(self.docker("volume", "inspect", "--format", '{"name":{{json .Name}},"driver":{{json .Driver}},"options":{{json .Options}},"labels":{{json .Labels}}}', target["dataVolume"]))
        require(volume["name"] == target["dataVolume"] and volume["driver"] == "local" and not volume["options"]
                and (volume["labels"] or {}).get("com.uteum.restore.scope") == "isolated-dev"
                and (volume["labels"] or {}).get("com.uteum.restore.operation") == self.p["operationId"], "DISTINCT_LOCAL_DRILL_VOLUME_REQUIRED")
        require(self.sql_identity(target["id"]) == (target["serverUuid"], "OFF"), "ISOLATED_DATABASE_CHANGED_OR_EVENTS_ENABLED")
        image = json.loads(self.docker("image", "inspect", "--format", '{"id":{{json .Id}},"digests":{{json .RepoDigests}},"labels":{{json .Config.Labels}}}', self.p["operatorImage"]["imageId"]))
        labels = image["labels"] or {}
        require(image["id"] == self.p["operatorImage"]["imageId"] and self.p["operatorImage"]["repoDigest"] in (image["digests"] or [])
                and labels.get("org.opencontainers.image.revision") == self.p["approvedSha"]
                and labels.get("com.uteum.dev-release-contract") == base.V65_CONTRACT
                and labels.get("com.uteum.flyway-catalog-sha256") == base.V65_CATALOG_SHA256
                and labels.get("com.uteum.deletion-ledger-format") == "1", "REVIEWED_OPERATOR_IMAGE_REQUIRED")

    def credentials(self, cid):
        # Process memory only. Never return these values in output, receipts or exception text.
        # Select three existing fields instead of retrieving the full container environment/root password.
        shell = 'printf "%s\\0%s\\0%s\\0" "${MYSQL_DATABASE:?}" "${MYSQL_USER:?}" "${MYSQL_PASSWORD:?}"'
        values = self.docker("exec", cid, "sh", "-c", shell).decode().split("\0")
        require(len(values) == 4 and values[3] == "" and values[0] == "edupilot" and bool(values[1])
                and values[1].lower() != "root" and bool(values[2]), "EXISTING_NON_ROOT_DATABASE_ROLE_REQUIRED")
        return values[1], values[2]

    def perform(self, action, ledger_path):
        # Share the maintenance CLI lock: install/reopen cannot race a ledger action.
        with maintenance.Journal(DEPLOY_ROOT, self.p["operationId"]).locked():
            return self._perform(action, ledger_path)

    def _perform(self, action, ledger_path):
        require(action in ("export", "migrate-isolated", "replay"), "EXPLICIT_LEDGER_ACTION_REQUIRED")
        require(self.state.is_absolute() and self.state.resolve() == self.state and not self.state.is_symlink()
                and not self.state.is_relative_to(SOURCE)
                and not self.state.is_relative_to(DEPLOY_ROOT / ".dev-maintenance"), "PRIVATE_STATE_OUTSIDE_SOURCE_AND_MAINTENANCE_JOURNAL_REQUIRED")
        self.state.mkdir(mode=0o700, exist_ok=True)
        require(self.state.parent.is_dir() and self.state.parent.resolve() == self.state.parent, "CANONICAL_STATE_PARENT_REQUIRED")
        require(os.name != "posix" or self.state.stat().st_mode & 0o077 == 0, "PRIVATE_STATE_REQUIRED")
        event = self.state / (self.p["operationId"] + "-" + action + ".json")
        require(not event.exists(), "ACTION_ALREADY_REQUESTED_INSPECT_NO_RETRY")
        self.verify()
        binding = base.sha_json({k: v for k, v in self.p.items() if k != "checkedAtUtc"})
        # Bind this session globally as well: changing --state-dir cannot reset consumed actions.
        index = maintenance.Journal(DEPLOY_ROOT, self.p["operationId"]).root / (self.p["operationId"] + "-ledger-state.json")
        expected_index = {"binding": binding, "stateDirectory": str(self.state)}
        if index.exists():
            require(maintenance.private_read(index) == expected_index, "OPERATOR_STATE_DIRECTORY_OR_CONTEXT_CHANGED")
        else:
            write_new(index, json.dumps(expected_index, sort_keys=True).encode())
        context = self.state / (self.p["operationId"] + ".json")
        if context.exists(): require(json.loads(private_path(context).read_text())["binding"] == binding, "OPERATION_CONTEXT_CHANGED")
        else: write_new(context, json.dumps({"binding": binding}).encode())
        raw = None
        if action == "export":
            ledger_path = private_path(ledger_path, must_exist=False)
        else:
            exported = json.loads(private_path(self.state / (self.p["operationId"] + "-export.json")).read_text())
            require(exported.get("phase") == "COMPLETE" and exported["binding"] == binding, "ACTUAL_EXPORT_REQUIRED")
            ledger_path = private_path(ledger_path)
            require(0 < ledger_path.stat().st_size <= MAX_BYTES, "LEDGER_FILE_SIZE_REJECTED")
            raw = ledger_path.read_bytes()
            require(0 < len(raw) <= MAX_BYTES and hashlib.sha256(raw).hexdigest() == exported["ledgerSha256"], "EXPORTED_LEDGER_CHANGED")
            if action == "replay":
                migrated = json.loads(private_path(self.state / (self.p["operationId"] + "-migrate-isolated.json")).read_text())
                require(migrated.get("phase") == "COMPLETE" and migrated["binding"] == binding, "ISOLATED_SCHEMA_VERIFICATION_REQUIRED")
                self.verify_isolated_schema()
        cid = self.p["services"]["mysql"]["id"] if action == "export" else self.p["isolated"]["id"]
        user, password = self.credentials(cid)
        child = dict(os.environ)
        values = {"LEDGER_DB_USER": user, "LEDGER_DB_PASSWORD": password, "LEDGER_WRAPPER_VERIFIED": "true",
                  "LEDGER_SOURCE_SHA": self.p["approvedSha"], "LEDGER_LIVE_UUID": self.p["liveServerUuid"],
                  "LEDGER_TARGET_UUID": self.p["liveServerUuid"] if action == "export" else self.p["isolated"]["serverUuid"],
                  "LEDGER_REVIEWED_AT": self.p["checkedAtUtc"], "LEDGER_BACKUP_AT": self.p["backupCreatedAtUtc"],
                  "LEDGER_RESTORE_EPOCH": self.p["restoreEpoch"], "LEDGER_INPUT_SHA256": hashlib.sha256(raw).hexdigest() if raw else ""}
        child.update(values)
        args = ["docker", "run", "--rm", "--pull", "never", "--name", "edupilot-ledger-" + self.p["operationId"] + "-" + action,
                "--interactive", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", "10001:10001",
                "--network", "container:" + cid, "--memory", str(self.p["operatorMemoryMb"]) + "m", "--cpus", str(self.p["operatorCpus"]),
                "--pids-limit", "64", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m"]
        for key in values: args.extend(["--env", key])
        args.extend(["--entrypoint", "java", self.p["operatorImage"]["repoDigest"], "-jar", "/app/deletion-ledger.jar", action, "--execute"])
        write_new(event, json.dumps({"phase": "INTENT", "binding": binding, "action": action, "isolatedWritesMayHaveOccurred": action != "export"}).encode())
        result = self.command(args, data=raw if action == "replay" else None, env=child, timeout=120)
        if action == "export":
            document = json.loads(result)
            require(document.get("kind") == "EDUPILOT_TRUSTED_DELETION_LEDGER_V1" and document.get("sourceSha") == self.p["approvedSha"]
                    and document.get("sourceUuid") == self.p["liveServerUuid"], "OPERATOR_EXPORT_PROTOCOL_MISMATCH")
            write_new(ledger_path, result)
            output = {"ledgerSha256": hashlib.sha256(result).hexdigest(), "sizeBytes": len(result), "entryCount": len(document["snapshots"])}
        else:
            document = json.loads(result)
            expected = "ISOLATED_SCHEMA_V65" if action == "migrate-isolated" else "ISOLATED_LOGICAL_REPLAY_COMPLETE"
            require(document.get("status") == expected, "OPERATOR_RECOVERY_INCOMPLETE_NO_REOPEN")
            if action == "migrate-isolated": self.verify_isolated_schema()
            output = document
        receipt = {"phase": "COMPLETE", "binding": binding, "action": action, **output}
        temp = event.with_suffix(".new")
        write_new(temp, json.dumps(receipt, sort_keys=True).encode()); os.replace(temp, event); sync_directory(event.parent)
        return {**output, "status": "OPERATOR_ACTION_COMPLETE_NOT_REOPEN_AUTHORIZATION", "action": action,
                "receiptSha256": hashlib.sha256(event.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--action", choices=("export", "migrate-isolated", "replay"), required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--ledger", type=Path)
    parser.add_argument("--state-dir", type=Path)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    try:
        plan = validate_plan(json.loads(private_path(args.plan).read_text(encoding="utf-8")))
        if not args.execute:
            print(json.dumps({"status": "LEDGER_PLAN_VALIDATED_NO_ACTION", "serverActions": 0})); return 0
        require(sys.platform.startswith("linux") and socket.gethostname() == HOST, "EXACT_LINUX_DEV_OPERATOR_REQUIRED")
        import pwd
        require(pwd.getpwuid(os.geteuid()).pw_name == "ec2-user", "EXISTING_DEV_OPERATOR_REQUIRED")
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=SOURCE, capture_output=True, check=True).stdout.decode().strip()
        dirty = subprocess.run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=SOURCE, capture_output=True, check=True).stdout
        require(head == plan["approvedSha"] and not dirty, "CLEAN_EXACT_REVIEWED_SOURCE_REQUIRED")
        require(args.ledger is not None and args.state_dir is not None, "PRIVATE_LEDGER_AND_STATE_PATHS_REQUIRED")
        print(json.dumps(Operator(plan, args.state_dir).perform(args.action, args.ledger), sort_keys=True)); return 0
    except Exception:
        print(json.dumps({"status": "OPERATOR_LEDGER_BLOCKED_OR_OUTCOME_UNKNOWN_NO_RETRY", "automaticRetryOrReopen": False})); return 2


if __name__ == "__main__":
    raise SystemExit(main())
