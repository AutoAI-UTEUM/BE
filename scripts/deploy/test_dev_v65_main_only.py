"""Synthetic V65 deployment contracts; no daemon, SSH, DB, registry or real files."""
import copy
import datetime as dt
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("reviewed_deploy", HERE / "dev_main_only.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
TARGET = "ghcr.io/autoai-uteum/main-service@sha256:" + "f" * 64


def fixture(root, baseline=64):
    for name in deploy.FILES:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, path)
    (root / ".env").write_text("SYNTHETIC_ONLY=unused\n", encoding="utf-8")
    (root / ".dev-signup-paused").touch()
    now = dt.datetime.now(dt.timezone.utc)
    backup = root / "backups/synthetic.sql.gz"
    backup.parent.mkdir()
    backup.write_bytes(gzip.compress(b"SYNTHETIC DUMP; NO PERSONAL DATA\n"))
    backup.chmod(0o600)
    catalog = deploy.source_catalog_v65(ROOT)
    services = {}
    for i, name in enumerate(deploy.SERVICES, 1):
        repo = "ghcr.io/autoai-uteum/" + name if name.endswith("-service") else name
        services[name] = {"id": str(i) * 64, "imageId": "sha256:" + str(i) * 64,
                          "repoDigest": repo + "@sha256:" + str(i) * 64}
    manifest = {
        "schemaVersion": 2, "approvedSha": "a" * 40, "preflightReceiptSha256": "b" * 64,
        "checkedAtUtc": now.isoformat(), "services": services,
        "configSha256": {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in deploy.FILES},
        "flywayCatalogSha256": deploy.V65_CATALOG_SHA256,
        "rollback": {"mainRepoDigest": services["main-service"]["repoDigest"], "registryAvailabilityReviewed": True},
        "budget": {"confirmed": 4, "reserved": 0, "limit": 7, "currentMainContainerId": services["main-service"]["id"], "evidenceSha256": "e" * 64},
        "ci": {"headSha": "a" * 40, "mainRunId": 1, "aiRunId": 2, "reviewedSuccess": True},
        "v65": {
            "releaseContract": deploy.V65_CONTRACT, "fromVersion": baseline,
            "guardianMysql": {"sourceSha": "c" * 40, "catalogSha256": deploy.V65_CATALOG_SHA256, "runId": 3, "reviewedSuccess": True},
            "backup": {"relativePath": "backups/synthetic.sql.gz", "createdAtUtc": (now - dt.timedelta(seconds=1)).isoformat(),
                       "sizeBytes": backup.stat().st_size, "sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
                       "flywayCatalogSha256": deploy.V64_CATALOG_SHA256 if baseline == 64 else deploy.V65_CATALOG_SHA256,
                       "restoreDrillReceiptSha256": "1" * 64, "filesRecoveryReceiptSha256": "2" * 64, "deletionLedgerReceiptSha256": "3" * 64},
            "recovery": {"mode": "COMPATIBLE_FORWARD_FIX_OR_VERIFIED_RESTORE", "evidenceSha256": "4" * 64, "automaticRollback": False},
            "writeQuiescence": {"confirmed": True, "startedAtUtc": (now - dt.timedelta(seconds=2)).isoformat(), "receiptSha256": "5" * 64},
        },
    }
    before = {n: {"id": s["id"], "imageId": s["imageId"], "repoDigests": [s["repoDigest"]],
                   "health": "healthy" if n != "nginx" else None} for n, s in services.items()}
    env = {k: "false" for k in deploy.GUARDIAN}
    env.update(SPRING_PROFILES_ACTIVE="dev", EDUPILOT_MAIL_PROVIDER="logging",
               EDUPILOT_MAIL_OUTBOX_DISPATCH_MODE="PAUSED", EDUPILOT_POLICY_SIGNUP_CONSENT_REQUIRED="true",
               EDUPILOT_JWT_SECRET="SYNTHETIC_SECRET_SENTINEL")
    return manifest, catalog, before, env


class V65Tests(unittest.TestCase):
    def test_catalog_rejects_v66_missing_duplicate_and_changed_old_or_new_migration(self):
        migrations = "main-service/src/main/resources/db/migration"
        for fault in ("v66", "missing", "duplicate", "v64_changed", "v65_changed", "v1_changed"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=HERE) as tmp:
                root = Path(tmp)
                target = root / migrations
                shutil.copytree(ROOT / migrations, target)
                if fault == "v66":
                    (target / "V66__unreviewed.sql").write_text("SELECT 1;")
                elif fault == "duplicate":
                    (target / "V65__duplicate.sql").write_text("SELECT 1;")
                else:
                    version = {"missing": 65, "v64_changed": 64, "v65_changed": 65, "v1_changed": 1}[fault]
                    path = next(target.glob(f"V{version}__*.sql"))
                    if fault == "missing":
                        path.unlink()
                    else:
                        path.write_text("SELECT 1;")
                with self.assertRaisesRegex(ValueError, "EXACT_REVIEWED_V65|DUPLICATE_MIGRATION"):
                    deploy.source_catalog_v65(root)

    def test_manifest_rejects_wrong_sha_catalog_baseline_ci_backup_recovery_or_policy_readiness(self):
        mutations = (
            lambda m: m.update(approvedSha="f" * 40),
            lambda m: m.update(schemaVersion=1),
            lambda m: m.update(flywayCatalogSha256="f" * 64),
            lambda m: m["v65"].update(fromVersion=63),
            lambda m: m["v65"].update(fromVersion=66),
            lambda m: m["v65"]["guardianMysql"].update(catalogSha256="f" * 64),
            lambda m: m["v65"]["guardianMysql"].update(reviewedSuccess=False),
            lambda m: m["ci"].update(headSha="f" * 40),
            lambda m: m["v65"]["backup"].update(relativePath="../secret.sql.gz"),
            lambda m: m["v65"]["backup"].update(sizeBytes=0),
            lambda m: m["v65"]["backup"].update(flywayCatalogSha256=deploy.V65_CATALOG_SHA256),
            lambda m: m["v65"]["backup"].update(restoreDrillReceiptSha256=""),
            lambda m: m["v65"]["backup"].update(filesRecoveryReceiptSha256=""),
            lambda m: m["v65"]["backup"].update(deletionLedgerReceiptSha256=""),
            lambda m: m["v65"]["recovery"].update(automaticRollback=True),
            lambda m: m["v65"]["recovery"].update(mode="OLD_IMAGE_ROLLBACK"),
            lambda m: m["v65"]["writeQuiescence"].update(confirmed=False),
            lambda m: m["v65"]["backup"].update(createdAtUtc=(dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)).isoformat()),
            lambda m: m["v65"]["writeQuiescence"].update(startedAtUtc=m["checkedAtUtc"]),
            lambda m: m["v65"].update(enabled=True),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate.__code__.co_firstlineno), tempfile.TemporaryDirectory(dir=HERE) as tmp:
                manifest, *_ = fixture(Path(tmp))
                mutate(manifest)
                with self.assertRaises(ValueError):
                    deploy.validate_v65_manifest(manifest, "a" * 40)

    def test_prepare_requires_explicit_v65_and_only_emits_compiled_payload(self):
        with tempfile.TemporaryDirectory(dir=HERE) as tmp:
            root = Path(tmp)
            manifest, *_ = fixture(root)
            for contract in ("v64", "v66", ""):
                with self.assertRaises(ValueError):
                    deploy.prepare(ROOT, root / "blocked.sh", manifest, "a" * 40, "SHA256:" + "a" * 43, contract)
                self.assertFalse((root / "blocked.sh").exists())
            output = root / "reviewed.sh"
            with mock.patch.object(deploy.subprocess, "run", side_effect=AssertionError("NO_PROCESS")) as process:
                value = deploy.prepare(ROOT, output, manifest, "a" * 40, "SHA256:" + "a" * 43, deploy.V65_CONTRACT)
            process.assert_not_called()
            self.assertEqual(value, deploy.V65_CATALOG_SHA256)
            script = output.read_text(encoding="utf-8")
            compile(script.split("\n", 2)[2].rsplit("\nEDUPILOT_REVIEWED_MAIN_ONLY_PY", 1)[0], "<synthetic-v65>", "exec")
            self.assertIn("'releaseContract': 'v65-prior-consent'", script)
            self.assertNotIn("SYNTHETIC_SECRET_SENTINEL", script)

    def test_prepare_cli_wrong_event_or_branch_never_emits_payload(self):
        for event, branch in (("push", "refs/heads/develop"), ("workflow_dispatch", "refs/heads/main"),
                              ("workflow_dispatch", "refs/heads/feature/synthetic")):
            with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": event, "GITHUB_REF": branch}), \
                 mock.patch.object(deploy.sys, "argv", ["deploy", "--prepare", "--release-contract", deploy.V65_CONTRACT, "--output", "not-created.sh"]), \
                 mock.patch.object(deploy, "prepare") as prepare:
                with self.assertRaisesRegex(ValueError, "MANUAL_DEVELOP_DISPATCH_ONLY"):
                    deploy.run()
                prepare.assert_not_called()

    def test_prepare_cli_writes_only_local_payload_and_catalog_output(self):
        with tempfile.TemporaryDirectory(dir=HERE) as tmp:
            root = Path(tmp)
            manifest, *_ = fixture(root)
            output, job_output = root / "reviewed.sh", root / "job-output"
            env = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/develop",
                   "GITHUB_SHA": "a" * 40, "REVIEWED_MANIFEST": json.dumps(manifest),
                   "VERIFIED_HOST_FINGERPRINT": "SHA256:" + "a" * 43, "GITHUB_OUTPUT": str(job_output)}
            console = io.StringIO()
            with mock.patch.dict(os.environ, env, clear=True), \
                 mock.patch.object(deploy.Path, "cwd", return_value=ROOT), \
                 mock.patch.object(deploy.sys, "argv", ["deploy", "--prepare", "--release-contract", deploy.V65_CONTRACT, "--output", str(output)]), \
                 mock.patch.object(deploy.subprocess, "run", side_effect=AssertionError("NO_PROCESS")) as process, \
                 mock.patch.object(deploy.sys, "stdout", console):
                self.assertEqual(deploy.run(), 0)
            process.assert_not_called()
            self.assertEqual(json.loads(console.getvalue())["serverConnections"], 0)
            self.assertEqual(job_output.read_text(encoding="utf-8"), "catalog_sha256=" + deploy.V65_CATALOG_SHA256 + "\n")
            self.assertTrue(output.is_file())

    def test_v65_remote_failure_receipt_preserves_change_state_without_retry_or_secret(self):
        with tempfile.TemporaryDirectory(dir=HERE) as tmp:
            root = Path(tmp).resolve()
            manifest, catalog, *_ = fixture(root)
            controller = mock.Mock(main_recreation_requested=True, nginx_reload_requested=False)
            controller.apply.side_effect = ValueError("SYNTHETIC_SECRET_DO_NOT_PRINT")
            console = io.StringIO()
            with mock.patch.object(deploy, "CONFIG", {"manifest": manifest, "catalog": catalog, "releaseContract": deploy.V65_CONTRACT}), \
                 mock.patch.dict(os.environ, {"DEV_DEPLOY_PATH": str(root), "NEW_MAIN_DIGEST": TARGET}, clear=True), \
                 mock.patch.object(deploy, "V65Deployment", return_value=controller) as factory, \
                 mock.patch.object(deploy, "Deployment", side_effect=AssertionError("V64_MUST_NOT_RUN")), \
                 mock.patch.object(deploy.sys, "argv", ["deploy", "--remote"]), \
                 mock.patch.object(deploy.sys, "stdout", console):
                self.assertEqual(deploy.run(), 2)
            factory.assert_called_once()
            controller.apply.assert_called_once()
            receipt = json.loads(console.getvalue())
            self.assertTrue(receipt["mainRecreationRequested"])
            self.assertFalse(receipt["nginxReloadRequested"])
            self.assertFalse(receipt["automaticRetryOrRollback"])
            self.assertNotIn("SYNTHETIC_SECRET", console.getvalue())

    def run_synthetic(self, baseline=64, fault=None):
        with tempfile.TemporaryDirectory(dir=HERE) as tmp:
            root = Path(tmp).resolve()
            manifest, catalog, before, env = fixture(root, baseline)
            controller = deploy.V65Deployment(root, manifest, catalog, TARGET)
            after = copy.deepcopy(before)
            after["main-service"].update(id="9" * 64, imageId="sha256:" + "f" * 64, repoDigests=[TARGET])
            if fault == "excluded_drift":
                after["mysql"]["id"] = "8" * 64
            if fault == "prior_policy":
                env[deploy.PRIOR_PREFIX + "ENABLED"] = "true"
            if fault == "guardian":
                env[deploy.GUARDIAN[0]] = "true"
            if fault == "backup_missing":
                (root / manifest["v65"]["backup"]["relativePath"]).unlink()
            if fault == "backup_hash":
                manifest["v65"]["backup"]["sha256"] = "0" * 64
            if fault == "backup_gzip":
                path = root / manifest["v65"]["backup"]["relativePath"]
                path.write_bytes(b"NOT GZIP")
                manifest["v65"]["backup"].update(sizeBytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())
            if fault == "no_signup_pause":
                (root / ".dev-signup-paused").unlink()
            calls = []
            target_config = {"Entrypoint": ["java", "-jar", "/app/app.jar"], "Cmd": None, "Env": [],
                             "Labels": {"org.opencontainers.image.revision": "a" * 40,
                                        "com.uteum.dev-release-contract": deploy.V65_CONTRACT,
                                        "com.uteum.flyway-catalog-sha256": deploy.V65_CATALOG_SHA256}}
            if fault in ("image_sha", "image_catalog", "image_contract"):
                key = {"image_sha": "org.opencontainers.image.revision", "image_catalog": "com.uteum.flyway-catalog-sha256", "image_contract": "com.uteum.dev-release-contract"}[fault]
                target_config["Labels"][key] = "UNREVIEWED"
            if fault == "image_policy":
                target_config["Env"] = [deploy.PRIOR_PREFIX + "ENABLED=true"]
            def fake(*args, data=None, timeout=20):
                calls.append((args, data))
                if args[0] == "inspect":
                    if args[-1] == before["nginx"]["id"]:
                        return "EDUPILOT_SIGNUP_PAUSED=" + ("false" if fault == "no_signup_pause" else "true") + "\n"
                    return json.dumps({"entrypoint": ["java", "-jar", "/app/app.jar"], "cmd": None})
                if args[:2] == ("exec", "-i"):
                    applied = controller.main_recreation_requested or baseline == 65
                    if b"flyway_schema_history" in data:
                        current = dict(catalog if applied else controller.baseline)
                        if fault == "baseline_history" and not controller.main_recreation_requested:
                            current["64"] += 1
                        if fault == "post_history" and controller.main_recreation_requested:
                            current.pop("65")
                        rows = [f"{v}\t{c}\t1" for v, c in current.items()]
                        if fault == "failed_migration":
                            rows[-1] = rows[-1][:-1] + "0"
                        if fault == "duplicate_history":
                            rows.append(rows[-1])
                        if fault == "future_history":
                            rows.append("66\t1\t1")
                        return "\n".join(rows)
                    if b"information_schema.columns" in data:
                        if (not applied and fault != "partial_baseline") or fault == "post_columns":
                            return ""
                        return "prior_consent_method\tvarchar(24)\tYES\nevidence_reviewed_at\tdatetime(6)\tYES\nprior_consent_valid_until\tdatetime(6)\tYES"
                    return "chk_guardian_team_prior_evidence\tYES" if applied and fault != "post_check" else ""
                if args[-3:] == ("config", "--format", "json"):
                    desired = dict(env)
                    if fault == "settings":
                        desired["EDUPILOT_JWT_SECRET"] = "CHANGED_SYNTHETIC_SECRET"
                    return json.dumps({"services": {n: {"image": TARGET if n == "main-service" else manifest["services"][n]["repoDigest"], "environment": desired if n == "main-service" else {}} for n in deploy.SERVICES}})
                if args[:2] == ("image", "inspect"):
                    return "sha256:" + "f" * 64 if args[3] == "{{.Id}}" else json.dumps(target_config)
                return "{}"
            with mock.patch.object(controller, "docker", side_effect=fake), \
                 mock.patch.object(controller, "identities", side_effect=lambda: after if controller.main_recreation_requested else before), \
                 mock.patch.object(controller, "main_environment", return_value=env), \
                 mock.patch.object(deploy.urllib.request, "urlopen", side_effect=[io.BytesIO(b'{"data":{"status":"UP"}}'), io.BytesIO(b'{"status":"UP","checks":{"db":"UP","aiService":"UP"}}')]), \
                 mock.patch.dict(os.environ, {"GHCR_TOKEN": "SYNTHETIC_TOKEN", "GHCR_USERNAME": "fixture"}, clear=True):
                try:
                    result = controller.apply()
                except (ValueError, OSError) as error:
                    result = error
            return controller, calls, result

    def test_exact_v64_to_v65_and_already_v65_use_one_main_up_and_verify_before_reload(self):
        for baseline in (64, 65):
            with self.subTest(baseline=baseline):
                controller, calls, result = self.run_synthetic(baseline)
                self.assertIsInstance(result, dict, str(result))
                self.assertEqual(result["status"], "MAIN_ONLY_DEPLOYED_READBACK_PASSED")
                commands = [c for c, _ in calls]
                replacements = [c for c in commands if "up" in c]
                self.assertEqual(len(replacements), 1)
                self.assertEqual(replacements[0][-7:], ("up", "-d", "--no-deps", "--no-build", "--pull", "never", "main-service"))
                self.assertEqual([c[-1] for c in commands if "pull" in c and "compose" not in c], [TARGET])
                reload_index = next(i for i, c in enumerate(commands) if c[-3:] == ("nginx", "-s", "reload"))
                post_sql = [i for i, (_, data) in enumerate(calls) if data and b"information_schema.table_constraints" in data]
                self.assertLess(post_sql[-1], reload_index)
                self.assertNotIn("restart", [x for c in commands for x in c])
                for _, data in calls:
                    if data and b"SELECT" in data:
                        self.assertIn(b"START TRANSACTION READ ONLY", data)
                        self.assertNotIn(b"UPDATE", data)

    def test_unready_backup_environment_or_schema_blocks_before_login_pull_and_up(self):
        for fault in ("backup_missing", "backup_hash", "backup_gzip", "no_signup_pause", "prior_policy", "guardian",
                      "baseline_history", "partial_baseline", "failed_migration", "duplicate_history", "future_history", "settings"):
            with self.subTest(fault=fault):
                controller, calls, result = self.run_synthetic(fault=fault)
                self.assertIsInstance(result, Exception)
                self.assertFalse(controller.main_recreation_requested)
                self.assertFalse(any("up" in c or "pull" in c or "login" in c for c, _ in calls))

    def test_unreviewed_image_never_recreates_main(self):
        for fault in ("image_sha", "image_catalog", "image_contract", "image_policy"):
            with self.subTest(fault=fault):
                controller, calls, result = self.run_synthetic(fault=fault)
                self.assertIsInstance(result, ValueError)
                self.assertFalse(controller.main_recreation_requested)
                self.assertFalse(any("up" in c for c, _ in calls))

    def test_partial_migration_or_excluded_drift_stops_without_nginx_reload_or_rollback(self):
        for fault in ("post_history", "post_columns", "post_check", "excluded_drift"):
            with self.subTest(fault=fault):
                controller, calls, result = self.run_synthetic(fault=fault)
                self.assertIsInstance(result, ValueError)
                self.assertTrue(controller.main_recreation_requested, str(result))
                self.assertFalse(controller.nginx_reload_requested)
                self.assertEqual(sum("up" in c for c, _ in calls), 1)
                self.assertFalse(any("rollback" in c or "restore" in c or "restart" in c for c, _ in calls))

    def test_prior_policy_values_are_not_assigned_from_year_end_test_plan(self):
        with tempfile.TemporaryDirectory(dir=HERE) as tmp:
            manifest, catalog, *_ = fixture(Path(tmp))
            controller = deploy.V65Deployment(Path(tmp).resolve(), manifest, catalog, TARGET)
            for suffix, value in (("ENABLED", "true"), ("POLICY_CONFIRMED", "true"), ("METHODS", "IN_PERSON"),
                                  ("MAX_AGE", "P365D"), ("POLICY_REFERENCE", "unreviewed")):
                with self.assertRaisesRegex(ValueError, "PRIOR_CONSENT_MUST_REMAIN"):
                    controller.prior_policy_off({deploy.PRIOR_PREFIX + suffix: value})
            controller.prior_policy_off({deploy.PRIOR_PREFIX + "ENABLED": "false"})

    def test_workflow_explicit_contract_build_labels_and_unconditional_ci_hook(self):
        text = (ROOT / ".github/workflows/deploy-dev.yml").read_text(encoding="utf-8")
        self.assertIn("default: v64", text)
        self.assertIn("- v65-prior-consent", text)
        self.assertIn('--release-contract "$RELEASE_CONTRACT"', text)
        self.assertLess(text.index("id: reviewed"), text.index("docker/build-push-action"))
        self.assertIn("com.uteum.flyway-catalog-sha256=${{ steps.reviewed.outputs.catalog_sha256 }}", text)
        self.assertNotIn("\n  push:", text)
        self.assertIn("inputs.approved_sha == github.sha", text)
        ci = (ROOT / ".github/workflows/main-service-ci.yml").read_text(encoding="utf-8")
        self.assertIn("      - name: Validate reviewed V65 DEV deployment guards without operations\n"
                      "        run: python3 -I -S -B scripts/deploy/test_dev_v65_main_only.py", ci)


if __name__ == "__main__":
    unittest.main(verbosity=2)
