"""Synthetic operator boundary tests; subprocesses are fakes, never Docker/SSH/MySQL."""
import contextlib
import copy
import datetime as dt
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("deletion_ledger", Path(__file__).with_name("deletion_ledger.py"))
p = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(p)


class BoundaryTests(unittest.TestCase):
    def setUp(self):
        parent = p.SOURCE.parent / "dev-ledger-local-tests"
        parent.mkdir(mode=0o700, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.deploy = self.root / "deploy"; self.deploy.mkdir(mode=0o700)
        self.patch = patch.object(p, "DEPLOY_ROOT", self.deploy); self.patch.start(); self.addCleanup(self.patch.stop)
        now = dt.datetime.now(dt.timezone.utc)
        self.at = (now - dt.timedelta(seconds=60)).isoformat()
        self.backup_at = (now - dt.timedelta(seconds=30)).isoformat()
        services = {name: {"id": str(i) * 64, "imageId": "sha256:" + str(i + 4) * 64}
                    for i, name in enumerate(p.base.SERVICES, 1)}
        op = "a" * 32
        self.p = {"schemaVersion": 1, "approvedSha": "b" * 40, "operationId": op, "checkedAtUtc": now.isoformat(),
                  "quiescenceReceiptSha256": "c" * 64, "backupCreatedAtUtc": self.backup_at,
                  "backupReceiptSha256": "d" * 64, "backupFile": {}, "services": services,
                  "liveServerUuid": "12345678-1234-1234-1234-123456789abc",
                  "operatorImage": {"imageId": "sha256:" + "9" * 64,
                                    "repoDigest": "ghcr.io/autoai-uteum/main-service@sha256:" + "e" * 64},
                  "isolated": {"id": "f" * 64, "serverUuid": "12345678-1234-1234-1234-123456789abd",
                               "dataVolume": "edupilot-restore-" + op + "-mysql"},
                  "restoreEpoch": "synthetic_restore", "operatorMemoryMb": 512, "operatorCpus": 1}
        directory = self.deploy / "backups"; directory.mkdir(mode=0o700)
        raw = gzip.compress(b"-- synthetic SQL only\nSELECT 1;\n")
        self.backup = directory / "synthetic.sql.gz"; p.write_new(self.backup, raw)
        self.p["backupFile"] = {"relativePath": "backups/synthetic.sql.gz", "sizeBytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
        self.journal = p.maintenance.Journal(self.deploy, op)
        with patch.object(p.maintenance, "utc", return_value=self.at):
            self.journal.root.mkdir(mode=0o700); self.journal.create()
            snapshot = {n: row | {"startedAt": self.at, "restartCount": 0, "ports": {}, "networkMode": "edupilot_net",
                                 "restartPolicy": "unless-stopped", "stopSignal": "SIGQUIT" if n == "nginx" else None}
                        for n, row in services.items()}
            self.journal.append("BASELINE", {"snapshot": snapshot, "targetImageId": self.p["operatorImage"]["imageId"]})
            self.journal.append("INGRESS_STOP_REQUESTED", {})
            self.journal.append("INGRESS_STOPPED", {"finishedAtUtc": self.at})
            self.journal.append("MAIN_STOP_REQUESTED", {})
            self.journal.append("MAIN_STOPPED", {"finishedAtUtc": self.at})
            last = self.journal.append("QUIESCED", {"drainReview": {}})
        self.p["quiescenceReceiptSha256"] = last["sha256"]
        self.items = {}
        for name, item in services.items():
            self.items[item["id"]] = item | {"running": name not in ("main-service", "nginx"),
                "paused": False, "restarting": False, "oom": False, "exitCode": 143 if name == "main-service" else 0,
                "health": None if name == "nginx" else "healthy", "finishedAt": self.at,
                "ports": {}, "networkMode": "edupilot_net", "networks": {"edupilot_net": {}}, "mounts": [], "labels": {}, "status": "exited" if name in ("main-service", "nginx") else "running",
                "startedAt": self.at, "restartCount": 0, "restartPolicy": "unless-stopped", "stopSignal": "SIGQUIT" if name == "nginx" else None}
        self.items["f" * 64] = {"id": "f" * 64, "imageId": services["mysql"]["imageId"], "running": True,
            "paused": False, "restarting": False, "oom": False, "exitCode": 0, "finishedAt": self.at,
            "health": None, "ports": {}, "networkMode": "none", "networks": {"none": {}}, "mounts": [{"Type": "volume", "Name": self.p["isolated"]["dataVolume"], "Destination": "/var/lib/mysql"}],
            "labels": {"com.uteum.restore.scope": "isolated-dev", "com.uteum.restore.operation": op}}
        self.image = {"id": self.p["operatorImage"]["imageId"], "digests": [self.p["operatorImage"]["repoDigest"]],
            "labels": {"org.opencontainers.image.revision": self.p["approvedSha"], "com.uteum.dev-release-contract": p.base.V65_CONTRACT,
                       "com.uteum.flyway-catalog-sha256": p.base.V65_CATALOG_SHA256, "com.uteum.deletion-ledger-format": "1"}}
        self.volume = {"name": self.p["isolated"]["dataVolume"], "driver": "local", "options": None,
                       "labels": {"com.uteum.restore.scope": "isolated-dev", "com.uteum.restore.operation": op}}
        self.schema_check_present = True
        self.calls = []; self.fail_child = False; self.events_enabled = False; self.user = "synthetic_app"
        self.ledger = self.root / "trusted-ledger.json"
        self.operator = p.Operator(self.p, self.root / "state", run=self.run_docker)

    def run_docker(self, args, **kwargs):
        self.calls.append((args, kwargs))
        self.assertEqual(args[0], "docker")
        if args[1] == "ps":
            name = args[-1].rsplit("=", 1)[1]; output = self.p["services"][name]["id"].encode()
        elif args[1] == "inspect":
            self.assertNotIn(".Config.Env", " ".join(args))
            output = json.dumps(self.items[args[-1]]).encode()
        elif args[1:3] == ["image", "inspect"]:
            output = json.dumps(self.image).encode()
        elif args[1:3] == ["volume", "inspect"]:
            output = json.dumps(self.volume).encode()
        elif args[1] == "exec":
            if args[-1].startswith("printf "):
                self.assertNotIn("MYSQL_ROOT_PASSWORD", args[-1])
                output = ("edupilot\0" + self.user + "\0synthetic-secret-never-output\0").encode()
            else:
                self.assertIn(b"TRANSACTION READ ONLY", kwargs["input"])
                if b"information_schema.columns" in kwargs["input"]:
                    self.assertEqual(args[3], self.p["isolated"]["id"])
                    output = b"prior_consent_method\tvarchar(24)\tYES\nevidence_reviewed_at\tdatetime(6)\tYES\nprior_consent_valid_until\tdatetime(6)\tYES\n"
                elif b"information_schema.table_constraints" in kwargs["input"]:
                    output = b"chk_guardian_team_prior_evidence\tYES\n" if self.schema_check_present else b""
                else:
                    target = args[3] == self.p["isolated"]["id"]
                    uuid = self.p["isolated"]["serverUuid"] if target else self.p["liveServerUuid"]
                    output = (uuid + "\t8.0.46\tedupilot\t" + ("OFF" if target and not self.events_enabled else "ON")).encode()
        elif args[1] == "run":
            if self.fail_child: raise subprocess.TimeoutExpired(args, 120)
            action = args[-2]
            if action == "export":
                output = json.dumps({"kind": "EDUPILOT_TRUSTED_DELETION_LEDGER_V1", "sourceSha": self.p["approvedSha"],
                    "sourceUuid": self.p["liveServerUuid"], "catalogSha256": p.base.V64_CATALOG_SHA256,
                    "exportedAtUtc": self.backup_at, "snapshots": []}).encode()
            else:
                output = json.dumps({"status": getattr(self, "child_status", None) or ("ISOLATED_SCHEMA_V65" if action == "migrate-isolated" else "ISOLATED_LOGICAL_REPLAY_COMPLETE"), "physicalDeletionExecuted": False}).encode()
        else: self.fail("Unexpected external command")
        return subprocess.CompletedProcess(args, 0, output, b"")

    def test_export_migration_replay_are_separate_and_only_target_copy_is_writable(self):
        for action in ("export", "migrate-isolated", "replay"):
            result = self.operator.perform(action, self.ledger)
            self.assertEqual(result["status"], "OPERATOR_ACTION_COMPLETE_NOT_REOPEN_AUTHORIZATION")
        children = [(a, k) for a, k in self.calls if a[1] == "run"]
        self.assertEqual(len(children), 3)
        self.assertEqual(children[0][0][children[0][0].index("--network") + 1], "container:" + self.p["services"]["mysql"]["id"])
        for args, kw in children[1:]:
            self.assertEqual(args[args.index("--network") + 1], "container:" + self.p["isolated"]["id"])
        for args, kw in children:
            self.assertNotIn("synthetic-secret-never-output", " ".join(args))
            self.assertIn("LEDGER_DB_PASSWORD", args)
            self.assertEqual(kw["env"]["LEDGER_DB_PASSWORD"], "synthetic-secret-never-output")
            for flag in ("--read-only", "--cap-drop", "--security-opt", "--user", "--memory", "--cpus", "--pids-limit"): self.assertIn(flag, args)
            self.assertNotIn("--privileged", args); self.assertNotIn("--volume", args)
        self.assertIsNone(children[1][1]["input"])
        self.assertEqual(children[2][1]["input"], self.ledger.read_bytes())
        self.assertFalse(self.journal.root.joinpath("action.lock").exists())

    def test_default_plan_validation_runs_no_commands(self):
        path = self.root / "plan.json"; p.write_new(path, json.dumps(self.p).encode())
        output = io.StringIO()
        with patch("sys.argv", ["deletion_ledger.py", "--action", "replay", "--plan", str(path)]), patch.object(p.subprocess, "run", side_effect=AssertionError("No subprocess allowed")), contextlib.redirect_stdout(output):
            self.assertEqual(p.main(), 0)
        self.assertIn("LEDGER_PLAN_VALIDATED_NO_ACTION", output.getvalue())
        self.assertEqual(self.calls, [])

    def test_short_or_unapproved_hostname_and_source_paths_fail_before_commands(self):
        path = self.root / "plan.json"; p.write_new(path, json.dumps(self.p).encode())
        for host in ("ip-172-31-40-63", p.HOST + ".unapproved.invalid"):
            with self.subTest(host=host), patch("sys.argv", ["deletion_ledger.py", "--action", "export", "--plan", str(path), "--execute"]), \
                    patch.object(p.sys, "platform", "linux"), patch.object(p.socket, "gethostname", return_value=host), \
                    patch.object(p.subprocess, "run", side_effect=AssertionError("No command allowed")), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(p.main(), 2)
        with self.assertRaises(Exception): p.private_path(p.SOURCE / "unapproved-ledger.json", must_exist=False)
        self.operator.state = self.journal.directory / "operator"
        with self.assertRaises(Exception): self.operator.perform("export", self.ledger)
        self.assertEqual(self.calls, [])

    def test_wrong_plan_target_stale_review_and_unbounded_resource_caps_fail_before_commands(self):
        mutations = [("schemaVersion", True), ("approvedSha", "bad"), ("checkedAtUtc", "2020-01-01T00:00:00Z"),
                     ("operatorMemoryMb", 8192), ("operatorCpus", True), ("operatorCpus", float("nan"))]
        for key, value in mutations:
            with self.subTest(key=key), self.assertRaises(Exception): p.validate_plan(self.p | {key: value})
        for key, value in [("id", self.p["services"]["mysql"]["id"]), ("serverUuid", self.p["liveServerUuid"]), ("dataVolume", "edupilot_mysql-data")]:
            plan = copy.deepcopy(self.p); plan["isolated"][key] = value
            with self.subTest(key=key), self.assertRaises(Exception): p.validate_plan(plan)
        self.assertEqual(self.calls, [])

    def test_target_ports_network_mount_labels_image_and_events_must_be_isolated(self):
        original = copy.deepcopy(self.items["f" * 64])
        changes = [("ports", {"3306/tcp": [{"HostPort": "3306"}]}), ("networkMode", "host"),
                   ("networks", {"none": {}, "edupilot_net": {}}),
                   ("mounts", [{"Type": "volume", "Name": "edupilot_mysql-data", "Destination": "/var/lib/mysql"}]),
                   ("labels", {"com.docker.compose.project": "edupilot"}), ("imageId", "sha256:" + "0" * 64), ("running", False)]
        for key, value in changes:
            self.items["f" * 64] = original | {key: value}
            with self.subTest(key=key), self.assertRaises(Exception): self.operator.verify()
        self.items["f" * 64] = original; self.events_enabled = True
        with self.assertRaises(Exception): self.operator.verify()

    def test_volume_alias_and_missing_v65_constraint_block_before_completed_recovery(self):
        self.volume["options"] = {"type": "none", "o": "bind", "device": "/synthetic/live-data"}
        with self.assertRaises(Exception): self.operator.verify()
        self.volume["options"] = None
        self.operator.perform("export", self.ledger)
        self.schema_check_present = False
        with self.assertRaises(Exception): self.operator.perform("migrate-isolated", self.ledger)
        event = self.operator.state / (self.p["operationId"] + "-migrate-isolated.json")
        self.assertEqual(json.loads(event.read_text())["phase"], "INTENT")
        with self.assertRaises(Exception): self.operator.perform("replay", self.ledger)

    def test_actual_journal_and_intended_stop_are_required(self):
        for name, key, value in [("main-service", "running", True), ("main-service", "exitCode", 137),
                                 ("nginx", "finishedAt", "2020-01-01T00:00:00Z"), ("ai-service", "health", "unhealthy"),
                                 ("mysql", "restartCount", 1), ("ai-service", "networkMode", "host")]:
            item = self.items[self.p["services"][name]["id"]]; before = item[key]; item[key] = value
            with self.subTest(name=name, key=key), self.assertRaises(Exception): self.operator.verify()
            item[key] = before
        self.journal.append("INSTALL_VALIDATION_STARTED", {})
        with self.assertRaises(Exception): self.operator.verify()

    def test_backup_hash_and_gzip_are_checked_without_restore(self):
        self.backup.write_bytes(b"x" * self.p["backupFile"]["sizeBytes"])
        with self.assertRaises(Exception): self.operator.verify_backup()
        self.p["backupFile"]["sha256"] = hashlib.sha256(self.backup.read_bytes()).hexdigest()
        with self.assertRaises(Exception): self.operator.verify_backup()
        self.assertEqual(self.calls, [])

    def test_root_credentials_and_unreviewed_operator_image_are_rejected(self):
        self.user = "root"
        with self.assertRaises(Exception): self.operator.perform("export", self.ledger)
        self.user = "synthetic_app"; self.image["labels"]["org.opencontainers.image.revision"] = "0" * 40
        with self.assertRaises(Exception): self.operator.verify()
        self.assertFalse(any(args[1] == "run" for args, _ in self.calls))

    def test_no_replay_before_export_migration_or_with_changed_file(self):
        with self.assertRaises(Exception): self.operator.perform("replay", self.ledger)
        self.operator.perform("export", self.ledger)
        with self.assertRaises(Exception): self.operator.perform("replay", self.ledger)
        self.operator.perform("migrate-isolated", self.ledger)
        self.ledger.write_bytes(self.ledger.read_bytes() + b" ")
        with self.assertRaises(Exception): self.operator.perform("replay", self.ledger)

    def test_timeout_leaves_intent_and_duplicate_action_never_retries(self):
        self.fail_child = True
        with self.assertRaises(subprocess.TimeoutExpired): self.operator.perform("export", self.ledger)
        event = self.operator.state / (self.p["operationId"] + "-export.json")
        self.assertEqual(json.loads(event.read_text())["phase"], "INTENT")
        count = len(self.calls)
        with self.assertRaises(Exception): self.operator.perform("export", self.ledger)
        self.assertEqual(len(self.calls), count)
        self.assertFalse(self.ledger.exists())

    def test_existing_maintenance_lock_blocks_all_actions(self):
        p.write_new(self.journal.root / "action.lock", b"synthetic")
        with self.assertRaises(FileExistsError): self.operator.perform("export", self.ledger)
        self.assertEqual(self.calls, [])

    def test_partial_or_identity_mismatch_result_is_not_a_completed_recovery(self):
        self.operator.perform("export", self.ledger)
        self.operator.perform("migrate-isolated", self.ledger)
        self.child_status = "IDENTITY_MISMATCH_HOLD"
        with self.assertRaises(Exception): self.operator.perform("replay", self.ledger)
        event = self.operator.state / (self.p["operationId"] + "-replay.json")
        self.assertEqual(json.loads(event.read_text())["phase"], "INTENT")
        count = len(self.calls)
        with self.assertRaises(Exception): self.operator.perform("replay", self.ledger)
        self.assertEqual(len(self.calls), count)

    def test_context_changes_are_not_silently_rebound(self):
        self.operator.perform("export", self.ledger)
        self.p["restoreEpoch"] = "different_epoch"
        with self.assertRaises(Exception): self.operator.perform("migrate-isolated", self.ledger)
        self.assertEqual(len([a for a, _ in self.calls if a[1] == "run"]), 1)

    def test_another_state_directory_cannot_reset_consumed_actions(self):
        self.operator.perform("export", self.ledger)
        other = p.Operator(self.p, self.root / "different-state", run=self.run_docker)
        with self.assertRaisesRegex(ValueError, "OPERATOR_STATE_DIRECTORY_OR_CONTEXT_CHANGED"):
            other.perform("export", self.root / "another-ledger.json")
        self.assertEqual(len([a for a, _ in self.calls if a[1] == "run"]), 1)

    def test_cli_errors_are_fixed_metadata_without_secrets(self):
        path = self.root / "plan.json"; p.write_new(path, b'{"password":"synthetic-secret-never-output"}')
        output = io.StringIO()
        with patch("sys.argv", ["deletion_ledger.py", "--action", "replay", "--plan", str(path)]), contextlib.redirect_stdout(output):
            self.assertEqual(p.main(), 2)
        self.assertNotIn("synthetic-secret", output.getvalue())
        self.assertIn("NO_RETRY", output.getvalue())


if __name__ == "__main__": unittest.main()
