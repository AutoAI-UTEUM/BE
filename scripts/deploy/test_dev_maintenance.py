"""Synthetic maintenance lifecycle. Every Docker/SQL/HTTP action is intercepted."""

import copy
import datetime as dt
import importlib.util
import io
import json
import os
import subprocess
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "maintenance", HERE / "dev_maintenance.py"
)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
spec = importlib.util.spec_from_file_location(
    "v65_fixtures", HERE / "test_dev_v65_main_only.py"
)
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)


class Simulation:
    def __init__(self, root, baseline=64):
        self.root = root
        self.manifest, self.catalog, _, self.env = fixtures.fixture(root, baseline)
        self.plan = {
            k: copy.deepcopy(v) for k, v in self.manifest.items() if k != "v65"
        }
        self.plan["schemaVersion"] = 3
        self.plan["maintenance"] = {
            "sessionId": "a" * 32,
            "fromVersion": baseline,
            "newMainRepoDigest": fixtures.TARGET,
            "guardianMysql": copy.deepcopy(self.manifest["v65"]["guardianMysql"]),
            "operatorPlanSha256": "6" * 64,
            "writerInventoryReceiptSha256": "7" * 64,
            "isolatedRecoveryPlanSha256": "8" * 64,
        }
        self.states = {}
        for n, s in self.plan["services"].items():
            self.states[n] = {
                "id": s["id"],
                "imageId": s["imageId"],
                "running": True,
                "paused": False,
                "restarting": False,
                "status": "running",
                "oomKilled": False,
                "exitCode": 0,
                "restartCount": 0,
                "startedAt": (
                    dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)
                ).isoformat(),
                "finishedAt": "0001-01-01T00:00:00Z",
                "health": "healthy" if n != "nginx" else None,
                "ports": {
                    "80/tcp": [{"HostPort": "80"}],
                    "443/tcp": [{"HostPort": "443"}],
                }
                if n == "nginx"
                else None,
                "networkMode": "edupilot_edupilot-net",
                "restartPolicy": "unless-stopped",
                "stopSignal": "SIGQUIT" if n == "nginx" else "SIGTERM",
            }
        self.calls, self.applied, self.fault = [], baseline == 65, None
        self.target = {
            "Entrypoint": ["java", "-jar", "/app/app.jar"],
            "Cmd": None,
            "Env": [],
            "Labels": {
                "org.opencontainers.image.revision": self.plan["approvedSha"],
                "com.uteum.dev-release-contract": m.base.V65_CONTRACT,
                "com.uteum.flyway-catalog-sha256": m.base.V65_CATALOG_SHA256,
            },
        }
        self.controller = m.Maintenance(root, self.plan, self.catalog)

    def docker(self, *args, data=None, timeout=20):
        self.calls.append((args, data, timeout))
        if args[0] == "ps":
            name = args[-1].rsplit("=", 1)[1]
            return self.states[name]["id"]
        if args[0] == "inspect":
            name = next(n for n, s in self.states.items() if s["id"] == args[-1])
            state, fmt = self.states[name], args[-2]
            if '"entrypoint"' in fmt:
                return json.dumps(
                    {"entrypoint": ["java", "-jar", "/app/app.jar"], "cmd": None}
                )
            if "range .Config.Env" in fmt:
                return "EDUPILOT_SIGNUP_PAUSED=true\n"
            if fmt == "{{json .Config.Env}}":
                return json.dumps([k + "=" + v for k, v in self.env.items()])
            if '"restartPolicy"' in fmt:
                return json.dumps({k: v for k, v in state.items() if k != "id"})
            if '"id":{{json .Id}}' in fmt:
                return json.dumps({k: state[k] for k in ("id", "imageId", "running")})
            return json.dumps(
                {
                    "imageId": state["imageId"],
                    "running": state["running"],
                    "health": state["health"],
                }
            )
        if args[:2] == ("image", "inspect"):
            if args[3] == "{{json .RepoDigests}}":
                n = next(n for n, s in self.states.items() if s["imageId"] == args[-1])
                return json.dumps(
                    [
                        fixtures.TARGET
                        if n == "main-service" and self.states[n]["id"] == "9" * 64
                        else self.plan["services"][n]["repoDigest"]
                    ]
                )
            return (
                "sha256:" + "f" * 64
                if args[3] == "{{.Id}}"
                else json.dumps(self.target)
            )
        if args[-3:] == ("config", "--format", "json"):
            env = dict(self.env)
            if self.fault == "settings":
                env["EDUPILOT_JWT_SECRET"] = "CHANGED_SYNTHETIC_SECRET"
            return json.dumps(
                {
                    "services": {
                        n: {
                            "image": fixtures.TARGET
                            if n == "main-service"
                            else self.plan["services"][n]["repoDigest"],
                            "environment": env if n == "main-service" else {},
                        }
                        for n in m.base.SERVICES
                    }
                }
            )
        if args[:2] == ("exec", "-i"):
            if b"flyway_schema_history" in data:
                catalog = {
                    v: c
                    for v, c in self.catalog.items()
                    if self.applied or int(v) <= 64
                }
                if self.fault == "post_migration" and self.applied:
                    catalog.pop("65")
                return "\n".join(f"{v}\t{c}\t1" for v, c in catalog.items())
            if b"information_schema.columns" in data:
                return (
                    (
                        "prior_consent_method\tvarchar(24)\tYES\nevidence_reviewed_at\tdatetime(6)\tYES\nprior_consent_valid_until\tdatetime(6)\tYES"
                    )
                    if self.applied
                    else ""
                )
            return "chk_guardian_team_prior_evidence\tYES" if self.applied else ""
        if args[0] == "stop":
            n = next(n for n, s in self.states.items() if s["id"] == args[-1])
            journal = self.controller.journal.read()
            assert journal[-1]["phase"] in (
                "INGRESS_STOP_REQUESTED",
                "MAIN_STOP_REQUESTED",
                "REBLOCK_REQUESTED",
            ) or (
                self.fault == "journal_unavailable_reblock"
                and journal[-1]["phase"] == "REOPEN_REQUESTED"
            )
            if self.fault == "stop_unknown":
                raise subprocess.TimeoutExpired("SYNTHETIC_DO_NOT_LOG", 120)
            self.states[n].update(running=False, status="exited", finishedAt=m.utc())
            if self.fault == "forced_main_exit" and n == "main-service":
                self.states[n]["exitCode"] = 137
            return ""
        if "up" in args:
            assert self.controller.journal.read()[-1]["phase"] == "MAIN_START_REQUESTED"
            self.applied = True
            self.states["main-service"].update(
                id="9" * 64,
                imageId="sha256:" + "f" * 64,
                running=True,
                status="running",
                finishedAt="0001-01-01T00:00:00Z",
                startedAt=m.utc(),
            )
            if self.fault == "up_unknown":
                raise OSError("SYNTHETIC_SECRET_DO_NOT_LOG")
            if self.fault == "candidate_restart":
                self.states["main-service"]["restartCount"] = 1
            return ""
        if args[0] == "start":
            assert self.controller.journal.read()[-1]["phase"] == "REOPEN_REQUESTED"
            self.states["nginx"].update(
                running=True, status="running", startedAt=m.utc()
            )
            return ""
        if args[0] == "exec" and "curl" in args:
            if self.fault == "private_health":
                return "{}"
            return (
                '{"status":"UP","checks":{"db":"UP","aiService":"UP"}}'
                if args[-1].endswith("ready")
                else '{"data":{"status":"UP"}}'
            )
        if "pull" in args and self.fault == "pull_unknown":
            raise OSError("SYNTHETIC_SECRET_DO_NOT_LOG")
        return "{}"

    @contextmanager
    def intercepted(self):
        # This catches accidental subprocess/HTTP escapes as well as expected fake calls.
        with (
            mock.patch.object(m.base.Deployment, "docker", side_effect=self.docker),
            mock.patch.object(
                m.base.subprocess, "run", side_effect=AssertionError("NO_REAL_PROCESS")
            ),
            mock.patch.object(
                m.base.urllib.request,
                "urlopen",
                side_effect=AssertionError("NO_REAL_HTTP"),
            ),
            mock.patch.dict(
                os.environ,
                {"GHCR_TOKEN": "SYNTHETIC_TOKEN", "GHCR_USERNAME": "fixture"},
                clear=True,
            ),
        ):
            yield

    def perform(self, action, evidence=None):
        with self.intercepted():
            return self.controller.perform(action, evidence)

    def quiesce(self):
        self.perform("begin")
        event = self.controller.journal.read()[-1]
        review = {
            "sessionId": self.plan["maintenance"]["sessionId"],
            "mainStoppedReceiptSha256": event["sha256"],
            "checkedAtUtc": m.utc(),
            "activeRequests": 0,
            "activeAiJobs": 0,
            "independentWriters": 0,
            "writerInventoryReceiptSha256": self.plan["maintenance"][
                "writerInventoryReceiptSha256"
            ],
            "drainEvidenceSha256": "b" * 64,
        }
        self.perform("confirm-drain", review)
        return review

    def backup_manifest(self):
        event = self.controller.journal.read()[-1]
        manifest = copy.deepcopy(self.manifest)
        manifest["v65"]["writeQuiescence"].update(
            startedAtUtc=event["atUtc"], receiptSha256=event["sha256"]
        )
        manifest["v65"]["backup"]["createdAtUtc"] = m.utc()
        manifest["checkedAtUtc"] = m.utc()
        return manifest

    def installed(self):
        self.quiesce()
        self.perform("install", self.backup_manifest())

    def reopen_review(self):
        event = self.controller.journal.read()[-1]
        budget = copy.deepcopy(self.plan["budget"])
        budget.update(
            confirmed=budget["confirmed"] + 1,
            currentMainContainerId=self.states["main-service"]["id"],
        )
        return {
            "sessionId": self.plan["maintenance"]["sessionId"],
            "verifiedReceiptSha256": event["sha256"],
            "checkedAtUtc": m.utc(),
            "allowIngress": True,
            "workersMayHaveRunAcknowledged": True,
            "budget": budget,
            "evidenceSha256": "c" * 64,
        }


class MaintenanceTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(dir=HERE, prefix="synthetic-maintenance-")
        self.addCleanup(temp.cleanup)
        self.sim = Simulation(Path(temp.name).resolve())

    def test_complete_lifecycle_requires_separate_reopen_and_only_one_main_up(self):
        s = self.sim
        s.installed()
        self.assertFalse(s.states["nginx"]["running"])
        self.assertTrue(s.controller.result("fixture")["workersMayHaveRun"])
        review = s.reopen_review()
        with (
            s.intercepted(),
            mock.patch.object(
                m.base.urllib.request,
                "urlopen",
                side_effect=[
                    io.BytesIO(b'{"data":{"status":"UP"}}'),
                    io.BytesIO(
                        b'{"status":"UP","checks":{"db":"UP","aiService":"UP"}}'
                    ),
                ],
            ),
        ):
            result = s.controller.perform("reopen", review)
        self.assertEqual(result["phase"], "REOPENED")
        self.assertFalse(s.controller.journal.active.exists())
        commands = [c for c, _, _ in s.calls]
        self.assertEqual(sum("up" in c for c in commands), 1)
        self.assertEqual(
            [c[-1] for c in commands if c[0] == "stop"], ["4" * 64, "1" * 64]
        )
        self.assertEqual([c[-1] for c in commands if c[0] == "start"], ["4" * 64])
        self.assertFalse(
            any(
                "restart" in c or "--remove-orphans" in c or "rollback" in c
                for c in commands
            )
        )
        self.assertTrue(all(c[-1] == fixtures.TARGET for c in commands if "pull" in c))
        self.assertTrue(
            all(c[1:3] == ("--timeout", "-1") for c in commands if c[0] == "stop")
        )
        for _, data, _ in s.calls:
            if data and b"SELECT" in data:
                self.assertIn(b"START TRANSACTION READ ONLY", data)
                self.assertFalse(
                    any(x in data for x in (b"UPDATE ", b"DELETE ", b"INSERT "))
                )

    def test_unhealthy_or_already_stopped_main_never_enters_maintenance(self):
        for key, value in (
            ("health", "unhealthy"),
            ("health", None),
            ("running", False),
            ("paused", True),
        ):
            s = self.sim
            original = copy.deepcopy(s.states)
            s.states["main-service"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                s.perform("begin")
            self.assertFalse(any(c[0] == "stop" for c, _, _ in s.calls))
            s.states = original

    def test_direct_ports_host_network_or_wrong_stop_signal_fail_before_stop(self):
        for name, key, value in (
            ("main-service", "ports", {"8080/tcp": [{"HostPort": "8080"}]}),
            ("ai-service", "networkMode", "host"),
            ("nginx", "restartPolicy", "always"),
            ("nginx", "stopSignal", "SIGKILL"),
        ):
            s = self.sim
            original = copy.deepcopy(s.states)
            s.states[name][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                s.perform("begin")
            self.assertFalse(any(c[0] == "stop" for c, _, _ in s.calls))
            s.states = original

    def test_image_revision_catalog_policy_and_settings_fail_before_stop(self):
        for fault in ("revision", "catalog", "policy", "settings"):
            s = self.sim
            target = copy.deepcopy(s.target)
            if fault == "revision":
                s.target["Labels"]["org.opencontainers.image.revision"] = "b" * 40
            if fault == "catalog":
                s.target["Labels"]["com.uteum.flyway-catalog-sha256"] = "b" * 64
            if fault == "policy":
                s.target["Env"] = [m.base.PRIOR_PREFIX + "ENABLED=true"]
            if fault == "settings":
                s.fault = "settings"
            with self.subTest(fault=fault), self.assertRaises(ValueError):
                s.perform("begin")
            self.assertFalse(any(c[0] == "stop" for c, _, _ in s.calls))
            s.target, s.fault = target, None

    def test_stop_timeout_does_not_stop_main_or_retry_and_duplicate_begin_is_rejected(
        self,
    ):
        s = self.sim
        s.fault = "stop_unknown"
        with self.assertRaises(subprocess.TimeoutExpired):
            s.perform("begin")
        self.assertTrue(s.states["main-service"]["running"])
        self.assertEqual(
            s.controller.journal.read()[-1]["phase"], "INGRESS_STOP_REQUESTED"
        )
        with self.assertRaises(ValueError):
            s.perform("begin")
        self.assertEqual(sum(c[0] == "stop" for c, _, _ in s.calls), 1)

    def test_sigkill_exit_is_not_claimed_as_graceful_quiescence(self):
        s = self.sim
        s.fault = "forced_main_exit"
        with self.assertRaises(ValueError):
            s.perform("begin")
        self.assertFalse(s.states["nginx"]["running"])
        self.assertEqual(
            s.controller.journal.read()[-1]["phase"], "MAIN_STOP_REQUESTED"
        )
        self.assertFalse(s.controller.result("fixture")["workersMayHaveRun"])

    def test_external_jobs_writers_wrong_target_and_stale_drain_review_are_refused(
        self,
    ):
        s = self.sim
        s.perform("begin")
        event = s.controller.journal.read()[-1]
        review = {
            "sessionId": s.controller.session,
            "mainStoppedReceiptSha256": event["sha256"],
            "checkedAtUtc": m.utc(),
            "activeRequests": 0,
            "activeAiJobs": 0,
            "independentWriters": 0,
            "writerInventoryReceiptSha256": "7" * 64,
            "drainEvidenceSha256": "b" * 64,
        }
        for key, value in (
            ("activeAiJobs", 1),
            ("independentWriters", 1),
            ("activeRequests", False),
            ("sessionId", "b" * 32),
            ("mainStoppedReceiptSha256", "0" * 64),
            (
                "checkedAtUtc",
                (dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)).isoformat(),
            ),
        ):
            bad = dict(review)
            bad[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                s.perform("confirm-drain", bad)
        self.assertEqual(s.controller.journal.read()[-1]["phase"], "MAIN_STOPPED")

    def test_missing_backup_does_not_start_main_and_stage_is_not_replayed(self):
        s = self.sim
        s.quiesce()
        manifest = s.backup_manifest()
        (s.root / manifest["v65"]["backup"]["relativePath"]).unlink()
        with self.assertRaises(ValueError):
            s.perform("install", manifest)
        with self.assertRaises(ValueError):
            s.perform("install", manifest)
        self.assertFalse(any("up" in c for c, _, _ in s.calls))
        self.assertFalse(s.states["nginx"]["running"])

    def test_old_receipt_other_baseline_and_reused_backup_are_rejected(self):
        s = self.sim
        s.quiesce()
        manifest = s.backup_manifest()
        for mutation in (
            lambda x: x["v65"]["writeQuiescence"].update(receiptSha256="0" * 64),
            lambda x: x["services"]["main-service"].update(id="8" * 64),
            lambda x: x["v65"]["backup"].update(
                createdAtUtc=(
                    dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)
                ).isoformat()
            ),
            lambda x: x["v65"]["backup"].update(restoreDrillReceiptSha256=""),
        ):
            bad = copy.deepcopy(manifest)
            mutation(bad)
            with self.subTest(bad=base_marker(bad)), self.assertRaises(ValueError):
                s.perform("install", bad)
        self.assertEqual(s.controller.journal.read()[-1]["phase"], "QUIESCED")
        self.assertFalse(any("up" in c for c, _, _ in s.calls))

    def test_up_outcome_unknown_is_not_retried_and_worker_risk_is_recorded(self):
        s = self.sim
        s.quiesce()
        manifest = s.backup_manifest()
        s.fault = "up_unknown"
        with self.assertRaises(OSError):
            s.perform("install", manifest)
        with self.assertRaises(ValueError):
            s.perform("install", manifest)
        self.assertEqual(sum("up" in c for c, _, _ in s.calls), 1)
        self.assertTrue(s.controller.result("fixture")["workersMayHaveRun"])
        self.assertFalse(s.states["nginx"]["running"])
        with self.assertRaises(ValueError):
            s.perform("reopen", {})

    def test_partial_migration_bad_private_health_and_restart_keep_ingress_stopped(
        self,
    ):
        # Each case gets an independent operation directory, including the baseline receipt.
        for fault in ("post_migration", "private_health", "candidate_restart"):
            with (
                self.subTest(fault=fault),
                tempfile.TemporaryDirectory(dir=HERE) as tmp,
            ):
                s = Simulation(Path(tmp).resolve())
                s.quiesce()
                s.fault = fault
                with self.assertRaises(ValueError):
                    s.perform("install", s.backup_manifest())
                self.assertFalse(s.states["nginx"]["running"])
                self.assertTrue(s.controller.result("fixture")["workersMayHaveRun"])
                self.assertFalse(any(c[0] == "start" for c, _, _ in s.calls))

    def test_reopen_without_separate_current_review_and_budget_cannot_start_nginx(self):
        s = self.sim
        s.installed()
        review = s.reopen_review()
        for mutation in (
            lambda x: x.update(allowIngress=False),
            lambda x: x.update(workersMayHaveRunAcknowledged=False),
            lambda x: x.update(verifiedReceiptSha256="0" * 64),
            lambda x: x["budget"].update(confirmed=4),
            lambda x: x["budget"].update(currentMainContainerId="1" * 64),
        ):
            bad = copy.deepcopy(review)
            mutation(bad)
            with self.assertRaises(ValueError):
                s.perform("reopen", bad)
        self.assertFalse(any(c[0] == "start" for c, _, _ in s.calls))

    def test_reopen_public_failure_or_interruption_closes_same_nginx_once(self):
        for error in (OSError("SYNTHETIC_SECRET"), KeyboardInterrupt()):
            with tempfile.TemporaryDirectory(dir=HERE) as tmp:
                s = Simulation(Path(tmp).resolve())
                s.installed()
                review = s.reopen_review()
                with (
                    s.intercepted(),
                    mock.patch.object(
                        m.base.urllib.request, "urlopen", side_effect=error
                    ),
                    self.assertRaises(type(error)),
                ):
                    s.controller.perform("reopen", review)
                self.assertFalse(s.states["nginx"]["running"])
                self.assertEqual(s.controller.journal.read()[-1]["phase"], "REBLOCKED")
                self.assertEqual(sum(c[0] == "start" for c, _, _ in s.calls), 1)
                self.assertEqual(sum(c[0] == "stop" for c, _, _ in s.calls), 3)
                with self.assertRaises(ValueError):
                    s.perform("reopen", review)

    def test_active_session_lock_and_corrupt_journal_fail_closed(self):
        s = self.sim
        s.perform("begin")
        with s.controller.journal.locked(), self.assertRaises(FileExistsError):
            s.perform("confirm-drain", {})
        other = copy.deepcopy(s.plan)
        other["maintenance"]["sessionId"] = "b" * 32
        with s.intercepted(), self.assertRaises(ValueError):
            m.Maintenance(s.root, other, s.catalog).perform("begin")
        path = s.controller.journal.directory / "0000.json"
        event = json.loads(path.read_text())
        event["data"]["targetImageId"] = "sha256:" + "0" * 64
        path.write_text(json.dumps(event), encoding="utf-8")
        with self.assertRaises(ValueError):
            s.controller.journal.read()

    def test_runtime_drift_or_unexpected_restart_rejects_confirm_drain(self):
        s = self.sim
        s.perform("begin")
        events = s.controller.journal.read()
        for name, key, value in (
            ("nginx", "running", True),
            ("mysql", "id", "8" * 64),
            ("ai-service", "health", "unhealthy"),
            ("main-service", "restartCount", 1),
        ):
            before = copy.deepcopy(s.states)
            s.states[name][key] = value
            with (
                s.intercepted(),
                self.subTest(name=name, key=key),
                self.assertRaises(ValueError),
            ):
                s.controller.held(events)
            s.states = before

    def test_plan_requires_exact_sha_catalog_budget_and_reviewed_resources(self):
        s = self.sim
        for mutate in (
            lambda x: x.update(approvedSha="b" * 40),
            lambda x: x["budget"].update(confirmed=6),
            lambda x: x["maintenance"].update(isolatedRecoveryPlanSha256=""),
            lambda x: x["maintenance"].update(
                newMainRepoDigest=s.plan["services"]["main-service"]["repoDigest"]
            ),
            lambda x: x["maintenance"]["guardianMysql"].update(reviewedSuccess=False),
        ):
            bad = copy.deepcopy(s.plan)
            mutate(bad)
            with self.assertRaises(ValueError):
                m.validate_plan(bad, "a" * 40, s.catalog)
        bad_catalog = dict(s.catalog)
        bad_catalog["66"] = 1
        with self.assertRaises(ValueError):
            m.validate_plan(s.plan, "a" * 40, bad_catalog)

    def test_default_cli_has_no_process_network_or_root_write(self):
        s = self.sim
        plan = s.root / "plan.json"
        m.private_write(plan, s.plan)
        output = io.StringIO()
        with (
            mock.patch.object(
                m.sys,
                "argv",
                [
                    "maintenance",
                    "--action",
                    "begin",
                    "--plan",
                    str(plan),
                    "--approved-sha",
                    "a" * 40,
                ],
            ),
            mock.patch.object(
                m.base.subprocess, "run", side_effect=AssertionError("NO_PROCESS")
            ) as process,
            mock.patch.object(
                m.base.urllib.request, "urlopen", side_effect=AssertionError("NO_HTTP")
            ) as http,
            mock.patch.object(m.sys, "stdout", output),
        ):
            self.assertEqual(m.run(), 0)
        process.assert_not_called()
        http.assert_not_called()
        self.assertEqual(
            json.loads(output.getvalue())["status"], "LOCAL_PLAN_VALIDATED_NO_ACTION"
        )
        self.assertFalse(s.controller.journal.root.exists())

    def test_status_does_not_attest_live_quiescence_or_run_docker(self):
        s = self.sim
        s.perform("begin")
        count = len(s.calls)
        result = s.perform("status")
        self.assertEqual(result["status"], "JOURNAL_ONLY_LIVE_STATE_NOT_ATTESTED")
        self.assertEqual(len(s.calls), count)
        self.assertNotIn("SYNTHETIC_SECRET", json.dumps(s.controller.journal.read()))

    def test_already_v65_baseline_uses_the_same_maintenance_guards(self):
        with tempfile.TemporaryDirectory(dir=HERE) as tmp:
            s = Simulation(Path(tmp).resolve(), baseline=65)
            s.installed()
            self.assertEqual(s.controller.journal.read()[-1]["phase"], "VERIFIED_HELD")
            self.assertEqual(sum("up" in c for c, _, _ in s.calls), 1)
            self.assertFalse(s.states["nginx"]["running"])

    def test_expiry_during_image_pull_is_rechecked_before_main_start(self):
        s = self.sim
        s.quiesce()
        manifest = s.backup_manifest()
        original = s.docker

        def elapsed(*args, **kwargs):
            result = original(*args, **kwargs)
            if "pull" in args:
                manifest["checkedAtUtc"] = (
                    dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)
                ).isoformat()
            return result

        with (
            s.intercepted(),
            mock.patch.object(m.base.Deployment, "docker", side_effect=elapsed),
            self.assertRaisesRegex(ValueError, "30_MINUTES"),
        ):
            s.controller.perform("install", manifest)
        self.assertFalse(any("up" in c for c, _, _ in s.calls))
        self.assertFalse(s.states["nginx"]["running"])

    def test_failure_to_reblock_is_reported_as_uncertain_and_not_retried(self):
        s = self.sim
        s.installed()
        review = s.reopen_review()
        s.fault = "stop_unknown"
        with (
            s.intercepted(),
            mock.patch.object(
                m.base.urllib.request, "urlopen", side_effect=OSError("SYNTHETIC")
            ),
            self.assertRaises(subprocess.TimeoutExpired),
        ):
            s.controller.perform("reopen", review)
        self.assertEqual(s.controller.journal.read()[-1]["phase"], "REBLOCK_REQUESTED")
        self.assertEqual(sum(c[0] == "stop" for c, _, _ in s.calls), 3)
        with self.assertRaises(ValueError):
            s.perform("reopen", review)

    def test_reblock_does_not_depend_on_other_services_remaining_healthy(self):
        s = self.sim
        s.installed()
        review = s.reopen_review()

        def public_failed(*args, **kwargs):
            s.states["ai-service"]["restarting"] = True
            raise OSError("SYNTHETIC")

        with (
            s.intercepted(),
            mock.patch.object(
                m.base.urllib.request, "urlopen", side_effect=public_failed
            ),
            self.assertRaises(OSError),
        ):
            s.controller.perform("reopen", review)
        self.assertFalse(s.states["nginx"]["running"])
        self.assertEqual(s.controller.journal.read()[-1]["phase"], "REBLOCKED")

    def test_journal_failure_during_reblock_does_not_skip_compensating_close(self):
        s = self.sim
        s.installed()
        review = s.reopen_review()
        s.fault = "journal_unavailable_reblock"
        append = s.controller.journal.append

        def log_failed(phase, data):
            if phase == "REBLOCK_REQUESTED":
                raise OSError("SYNTHETIC_DISK_FAILURE")
            return append(phase, data)

        with (
            s.intercepted(),
            mock.patch.object(
                m.base.urllib.request, "urlopen", side_effect=OSError("SYNTHETIC")
            ),
            mock.patch.object(s.controller.journal, "append", side_effect=log_failed),
            self.assertRaises(OSError),
        ):
            s.controller.perform("reopen", review)
        self.assertFalse(s.states["nginx"]["running"])

    def test_cli_rejects_unreviewed_source_before_host_or_server_actions(self):
        s = self.sim
        plan = s.root / "plan.json"
        m.private_write(plan, s.plan)
        argv = [
            "maintenance",
            "--action",
            "begin",
            "--plan",
            str(plan),
            "--approved-sha",
            "a" * 40,
            "--execute",
        ]
        responses = [
            subprocess.CompletedProcess([], 0, stdout=b"b" * 40),
            subprocess.CompletedProcess([], 0, stdout=b""),
        ]
        with (
            mock.patch.object(m.sys, "argv", argv),
            mock.patch.object(m.sys, "platform", "linux"),
            mock.patch.object(
                m.base.subprocess, "run", side_effect=responses
            ) as process,
            self.assertRaisesRegex(ValueError, "CLEAN_EXACT_REVIEWED_SOURCE"),
        ):
            m.run()
        self.assertEqual(process.call_count, 2)
        self.assertFalse(s.controller.journal.root.exists())

    def test_output_or_entry_failure_preserves_uncertainty_without_raw_exception(self):
        console = io.StringIO()
        with (
            mock.patch.object(m, "run", side_effect=OSError("SYNTHETIC_SECRET")),
            mock.patch.object(m.sys, "stdout", console),
        ):
            self.assertEqual(m.entry(), 2)
        result = json.loads(console.getvalue())
        self.assertEqual(
            result["status"], "BLOCKED_INPUT_READ_OR_OUTPUT_FAILED_OUTCOME_UNCONFIRMED"
        )
        self.assertNotIn("SYNTHETIC_SECRET", console.getvalue())
        self.assertNotIn("NO_ACTION", result["status"])


def base_marker(value):
    return m.base.sha_json(value)


if __name__ == "__main__":
    unittest.main(verbosity=2)
