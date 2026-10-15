"""Synthetic release guard tests. No Docker daemon, SSH, registry, HTTP or real keys."""
import copy
import datetime as dt
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("dev_main_only", HERE / "dev_main_only.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


def fixture():
    services = {}
    for i, name in enumerate(deploy.SERVICES, 1):
        repo = "ghcr.io/autoai-uteum/" + name if name.endswith("-service") else name
        services[name] = {"id": str(i) * 64, "imageId": "sha256:" + str(i) * 64,
                          "repoDigest": repo + "@sha256:" + str(i) * 64}
    catalog = deploy.source_catalog(ROOT)
    manifest = {"schemaVersion": 1, "approvedSha": "a" * 40, "preflightReceiptSha256": "b" * 64,
                "checkedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(), "services": services,
                "configSha256": {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in deploy.FILES},
                "flywayCatalogSha256": deploy.sha_json(catalog),
                "rollback": {"mainRepoDigest": services["main-service"]["repoDigest"], "registryAvailabilityReviewed": True},
                "budget": {"confirmed": 4, "reserved": 0, "limit": 7, "currentMainContainerId": services["main-service"]["id"], "evidenceSha256": "e" * 64},
                "ci": {"headSha": "a" * 40, "mainRunId": 1, "aiRunId": 2, "reviewedSuccess": True}}
    before = {name: {"id": v["id"], "imageId": v["imageId"], "repoDigests": [v["repoDigest"]],
                     "health": "healthy" if name != "nginx" else None} for name, v in services.items()}
    old_env = {"EDUPILOT_MAIL_PROVIDER": "logging", "EDUPILOT_MAIL_OUTBOX_DISPATCH_MODE": "PAUSED",
               "EDUPILOT_JWT_SECRET": "SYNTHETIC_SECRET_SENTINEL"}
    return manifest, catalog, before, old_env


class MainOnlyTests(unittest.TestCase):
    def test_manifest_rejects_stale_future_wrong_sha_and_incomplete_evidence(self):
        for mutate in (
            lambda m: m.update(approvedSha="f" * 40),
            lambda m: m.update(checkedAtUtc=(dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=31)).isoformat()),
            lambda m: m.update(checkedAtUtc=(dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=1)).isoformat()),
            lambda m: m["rollback"].update(registryAvailabilityReviewed=False),
            lambda m: m["rollback"].update(mainRepoDigest="ghcr.io/autoai-uteum/main-service@sha256:" + "f" * 64),
            lambda m: m["ci"].update(headSha="f" * 40),
            lambda m: m["budget"].update(confirmed=6),
            lambda m: m["services"]["mysql"].update(repoDigest="sha256:" + "1" * 64),
            lambda m: m.update(secret="SYNTHETIC_SECRET_SENTINEL"),
        ):
            manifest, *_ = fixture()
            mutate(manifest)
            with self.assertRaises(ValueError):
                deploy.validate_manifest(manifest, "a" * 40)

    def test_valid_manifest_requires_main_and_manual_recovery_budget(self):
        manifest, *_ = fixture()
        deploy.validate_manifest(manifest, "a" * 40)
        manifest["budget"]["reserved"] = 2
        with self.assertRaisesRegex(ValueError, "CURRENT_BUDGET"):
            deploy.validate_manifest(manifest, "a" * 40)

    def test_main_rollback_does_not_require_teammate_owned_fe_artifacts(self):
        manifest, *_ = fixture()
        self.assertEqual(set(manifest["rollback"]), {"mainRepoDigest", "registryAvailabilityReviewed"})
        deploy.validate_manifest(manifest, "a" * 40)
        del manifest["rollback"]["mainRepoDigest"]
        with self.assertRaises(ValueError):
            deploy.validate_manifest(manifest, "a" * 40)

    def test_prepare_only_compiles_a_bound_script_and_never_connects(self):
        manifest, *_ = fixture()
        with tempfile.TemporaryDirectory(dir=HERE, prefix="synthetic-") as tmp:
            output = Path(tmp) / "main-only.sh"
            with mock.patch.object(deploy.subprocess, "run", side_effect=AssertionError("NO_PROCESS")):
                deploy.prepare(ROOT, output, manifest, "a" * 40, "SHA256:" + "a" * 43)
            source = output.read_text(encoding="utf-8")
            self.assertIn("python3 -I -S -B - --remote", source)
            self.assertIn("'manifest':", source)
            self.assertNotIn("SYNTHETIC_SECRET_SENTINEL", source)
            python = source.split("\n", 2)[2].rsplit("\nEDUPILOT_REVIEWED_MAIN_ONLY_PY", 1)[0]
            compile(python, "<synthetic-remote>", "exec")

    def test_prepare_rejects_changed_source_config_and_missing_host_trust(self):
        manifest, *_ = fixture()
        for fingerprint in ("", "SHA256:invalid", "accept-new"):
            with self.assertRaises(ValueError):
                deploy.prepare(ROOT, HERE / "not-created.sh", manifest, "a" * 40, fingerprint)
        manifest["configSha256"]["docker-compose.dev.yml"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "REVIEWED_HOST_CONFIG"):
            deploy.prepare(ROOT, HERE / "not-created.sh", manifest, "a" * 40, "SHA256:" + "a" * 43)
        self.assertFalse((HERE / "not-created.sh").exists())

    def run_synthetic(self, *, drift=False, settings_drift=False, initial_fail=False):
        manifest, catalog, before, env = fixture()
        target = "ghcr.io/autoai-uteum/main-service@sha256:" + "f" * 64
        after = copy.deepcopy(before)
        after["main-service"].update(id="9" * 64, imageId="sha256:" + "f" * 64, repoDigests=[target])
        if drift:
            after["mysql"]["id"] = "8" * 64
        controller = deploy.Deployment(ROOT, manifest, catalog, target)
        calls = []
        def fake(*args, data=None, timeout=20):
            calls.append((args, data))
            if "config" in args and args[-3:] == ("config", "--format", "json"):
                desired = dict(env)
                if settings_drift:
                    desired["EDUPILOT_JWT_SECRET"] = "SYNTHETIC_CHANGED_SECRET"
                return json.dumps({"services": {n: {"image": target if n == "main-service" else manifest["services"][n]["repoDigest"], "environment": desired if n == "main-service" else {}} for n in deploy.SERVICES}})
            if args[:2] == ("image", "inspect"):
                if args[3] == "{{.Id}}":
                    return "sha256:" + "f" * 64
                return json.dumps({"Entrypoint": ["java", "-jar", "/app/app.jar"], "Cmd": None, "Env": ["PATH=/synthetic"]})
            return "{}"
        with mock.patch.object(controller, "verify_readonly", side_effect=ValueError("BLOCKED") if initial_fail else lambda: (before, env, "false")), \
             mock.patch.object(controller, "identities", return_value=after), \
             mock.patch.object(controller, "main_environment", return_value=env), \
             mock.patch.object(controller, "docker", side_effect=fake), \
             mock.patch.object(deploy.tempfile, "tempdir", str(HERE)), \
             mock.patch.object(deploy.urllib.request, "urlopen", side_effect=[io.BytesIO(b'{"data":{"status":"UP"}}'), io.BytesIO(b'{"status":"UP","checks":{"db":"UP","aiService":"UP"}}')]), \
             mock.patch.dict(os.environ, {"GHCR_TOKEN": "SYNTHETIC_JOB_TOKEN", "GHCR_USERNAME": "fixture"}):
            try:
                result = controller.apply()
            except ValueError as error:
                return controller, calls, error
        return controller, calls, result

    def test_only_new_main_is_pulled_up_once_and_nginx_only_reloads(self):
        controller, calls, result = self.run_synthetic()
        self.assertEqual(result["status"], "MAIN_ONLY_DEPLOYED_READBACK_PASSED")
        commands = [args for args, _ in calls]
        pulls = [c for c in commands if "pull" in c and "compose" not in c]
        self.assertEqual(len(pulls), 1)
        self.assertEqual(pulls[0][-1], result["mainRepoDigest"])
        ups = [c for c in commands if "up" in c]
        self.assertEqual(len(ups), 1)
        self.assertEqual(ups[0][-7:], ("up", "-d", "--no-deps", "--no-build", "--pull", "never", "main-service"))
        self.assertTrue(controller.nginx_reload_requested)
        self.assertFalse(any("restart" in c or "--remove-orphans" in c or "down" in c for c in commands))
        self.assertFalse(any("/usr/share/nginx/html" in arg or "frontend-static" in arg or arg in ("cp", "tar", "rm")
                             for c in commands for arg in c))
        self.assertFalse(any("SYNTHETIC_JOB_TOKEN" in arg or "SYNTHETIC_SECRET_SENTINEL" in arg for c in commands for arg in c))

    def test_infrastructure_drift_stops_without_second_up_or_rollback(self):
        controller, calls, result = self.run_synthetic(drift=True)
        self.assertIsInstance(result, ValueError)
        self.assertTrue(controller.main_recreation_requested)
        self.assertFalse(controller.nginx_reload_requested)
        self.assertEqual(sum("up" in args for args, _ in calls), 1)

    def test_settings_drift_or_failed_preflight_prevents_any_pull_or_up(self):
        for option in ({"settings_drift": True}, {"initial_fail": True}):
            controller, calls, result = self.run_synthetic(**option)
            self.assertIsInstance(result, ValueError)
            self.assertFalse(controller.main_recreation_requested)
            self.assertFalse(any("up" in args or "pull" in args or "login" in args for args, _ in calls))

    def test_workflow_never_deploys_on_push_and_retains_existing_permissions(self):
        text = (ROOT / ".github/workflows/deploy-dev.yml").read_text(encoding="utf-8")
        self.assertNotIn("\n  push:", text)
        self.assertIn("workflow_dispatch:", text)
        self.assertIn("inputs.approved_sha == github.sha", text)
        self.assertIn("fingerprint: ${{ inputs.verified_host_fingerprint }}", text)
        self.assertIn("contents: read\n  packages: write", text)
        self.assertNotIn("ai-service/Dockerfile", text)
        self.assertNotIn(":latest", text)
        self.assertNotIn("scp-action", text)

    def test_remote_readonly_guards_reject_runtime_schema_drift_without_fe_access(self):
        for fault in (None, "guardian", "flyway", "identity", "config", "nginx_pause"):
            manifest, catalog, before, _ = fixture()
            env = {k: "false" for k in deploy.GUARDIAN}
            env.update(SPRING_PROFILES_ACTIVE="dev", EDUPILOT_MAIL_PROVIDER="logging",
                       EDUPILOT_MAIL_OUTBOX_DISPATCH_MODE="PAUSED",
                       EDUPILOT_POLICY_SIGNUP_CONSENT_REQUIRED="true")
            if fault == "guardian":
                env["EDUPILOT_GUARDIAN_WEB_ENABLED"] = "true"
            if fault == "identity":
                before["mysql"]["id"] = "8" * 64
            calls = []
            def fake(*args, data=None, timeout=20):
                calls.append((args, data))
                if args[0] == "inspect":
                    if args[-1] == before["nginx"]["id"]:
                        return 'EDUPILOT_SIGNUP_PAUSED=' + ('true' if fault == 'nginx_pause' else 'false') + '\n\n'
                    return json.dumps({"entrypoint": ["java", "-jar", "/app/app.jar"], "cmd": None})
                if args[:2] == ("exec", "-i"):
                    rows = dict(catalog)
                    if fault == "flyway":
                        rows["64"] += 1
                    return "\n".join(f"{v}\t{checksum}\t1" for v, checksum in rows.items())
                return ""
            with tempfile.TemporaryDirectory(dir=HERE, prefix="synthetic-") as tmp:
                root = Path(tmp)
                for filename in deploy.FILES:
                    path = root / filename
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes((ROOT / filename).read_bytes())
                (root / ".env").write_text("UNRELATED_SECRET=SYNTHETIC_SECRET_SENTINEL\n", encoding="utf-8")
                if fault == "config":
                    (root / "docker-compose.dev.yml").write_text("changed", encoding="utf-8")
                controller = deploy.Deployment(root, manifest, catalog, "ghcr.io/autoai-uteum/main-service@sha256:" + "f" * 64)
                with mock.patch.object(controller, "identities", return_value=before), \
                        mock.patch.object(controller, "main_environment", return_value=env), \
                        mock.patch.object(controller, "docker", side_effect=fake):
                    if fault is None:
                        controller.verify_readonly()
                    else:
                        with self.assertRaises(ValueError, msg=fault):
                            controller.verify_readonly()
            self.assertFalse(any("up" in args or "pull" in args or "login" in args for args, _ in calls))
            self.assertFalse(any("/usr/share/nginx/html" in arg or "frontend-static" in arg or "sha256sum" in arg
                                 for args, _ in calls for arg in args))
            for _, sql in calls:
                if sql:
                    self.assertIn(b"START TRANSACTION READ ONLY", sql)
                    self.assertNotIn(b"UPDATE", sql)
                    self.assertNotIn(b"encrypted_payload", sql)

    def test_nginx_pause_rejects_missing_duplicate_or_unsupported_values(self):
        manifest, catalog, _, _ = fixture()
        controller = deploy.Deployment(ROOT, manifest, catalog, "ghcr.io/autoai-uteum/main-service@sha256:" + "f" * 64)
        with mock.patch.object(controller, 'docker', return_value='EDUPILOT_SIGNUP_PAUSED=false\n\n'):
            self.assertEqual(controller.nginx_signup_pause('4' * 64), 'false')
        for text in ('\n', 'EDUPILOT_SIGNUP_PAUSED=false\nEDUPILOT_SIGNUP_PAUSED=true\n', 'EDUPILOT_SIGNUP_PAUSED=FALSE\n'):
            with mock.patch.object(controller, 'docker', return_value=text), self.assertRaises(ValueError):
                controller.nginx_signup_pause('4' * 64)


if __name__ == "__main__":
    unittest.main(verbosity=2)
