"""Synthetic release guard tests. No Docker daemon, SSH, registry, HTTP or real keys."""
import copy
import datetime as dt
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
spec = importlib.util.spec_from_file_location("dev_main_only", HERE / "dev_main_only.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
RELEASE_ROOT = None


def setUpModule():
    """Keep the reviewed V64 release fixture separate from the evolving checkout."""
    global RELEASE_ROOT
    directory = tempfile.TemporaryDirectory(dir=HERE, prefix="synthetic-v64-release-")
    RELEASE_ROOT = Path(directory.name).resolve()
    assert RELEASE_ROOT.is_relative_to(HERE.resolve())
    unittest.addModuleCleanup(directory.cleanup)
    for filename in deploy.FILES:
        target = RELEASE_ROOT / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / filename).read_bytes())
    migrations = "main-service/src/main/resources/db/migration"
    destination = RELEASE_ROOT / migrations
    destination.mkdir(parents=True)
    for version in range(1, 65):
        sources = list((ROOT / migrations).glob(f"V{version}__*.sql"))
        assert len(sources) == 1, f"Expected exactly one reviewed V{version} migration"
        shutil.copyfile(sources[0], destination / sources[0].name)


def fixture():
    services = {}
    for i, name in enumerate(deploy.SERVICES, 1):
        repo = "ghcr.io/autoai-uteum/" + name if name.endswith("-service") else name
        services[name] = {"id": str(i) * 64, "imageId": "sha256:" + str(i) * 64,
                          "repoDigest": repo + "@sha256:" + str(i) * 64}
    catalog = deploy.source_catalog(RELEASE_ROOT)
    manifest = {"schemaVersion": 1, "approvedSha": "a" * 40, "preflightReceiptSha256": "b" * 64,
                "checkedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(), "services": services,
                "configSha256": {p: hashlib.sha256((RELEASE_ROOT / p).read_bytes()).hexdigest() for p in deploy.FILES},
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
    def test_release_fixture_is_exactly_v64_while_current_checkout_is_rejected(self):
        catalog = deploy.source_catalog(RELEASE_ROOT)
        self.assertEqual(set(catalog), {str(v) for v in range(1, 65)})
        self.assertEqual(catalog["64"], -1987207835)
        self.assertTrue(any(int(path.name.split("__", 1)[0][1:]) > 64
                            for path in (ROOT / "main-service/src/main/resources/db/migration").glob("V*__*.sql")))
        with self.assertRaisesRegex(ValueError, "V64_ONLY_RELEASE_EXPECTED"):
            deploy.source_catalog(ROOT)

    def test_current_checkout_cannot_prepare_a_v64_release_script(self):
        manifest, *_ = fixture()
        with tempfile.TemporaryDirectory(dir=HERE, prefix="synthetic-post-v64-") as temp:
            self.assertTrue(Path(temp).resolve().is_relative_to(HERE))
            output = Path(temp) / "not-authorized.sh"
            with mock.patch.object(deploy.subprocess, "run", side_effect=AssertionError("NO_PROCESS")) as process:
                with self.assertRaisesRegex(ValueError, "V64_ONLY_RELEASE_EXPECTED"):
                    deploy.prepare(ROOT, output, manifest, "a" * 40, "SHA256:" + "a" * 43)
            self.assertFalse(output.exists())
            process.assert_not_called()

    def test_release_catalog_still_rejects_added_missing_or_modified_migrations(self):
        migrations = "main-service/src/main/resources/db/migration"
        for fault in ("newer", "missing", "modified", "duplicate"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=HERE, prefix="synthetic-catalog-") as temp:
                root = Path(temp)
                self.assertTrue(root.resolve().is_relative_to(HERE))
                target = root / migrations
                shutil.copytree(RELEASE_ROOT / migrations, target)
                v64 = next(target.glob("V64__*.sql"))
                if fault == "newer":
                    (target / "V65__unreviewed_synthetic.sql").write_text("SELECT 1;\n", encoding="utf-8")
                elif fault == "missing":
                    v64.unlink()
                elif fault == "modified":
                    v64.write_text("SELECT 1;\n", encoding="utf-8")
                else:
                    (target / "V64__duplicate_synthetic.sql").write_text("SELECT 1;\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "V64_ONLY_RELEASE_EXPECTED|DUPLICATE_MIGRATION_VERSION"):
                    deploy.source_catalog(root)

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
                deploy.prepare(RELEASE_ROOT, output, manifest, "a" * 40, "SHA256:" + "a" * 43)
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
                deploy.prepare(RELEASE_ROOT, HERE / "not-created.sh", manifest, "a" * 40, fingerprint)
        manifest["configSha256"]["docker-compose.dev.yml"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "REVIEWED_HOST_CONFIG"):
            deploy.prepare(RELEASE_ROOT, HERE / "not-created.sh", manifest, "a" * 40, "SHA256:" + "a" * 43)
        self.assertFalse((HERE / "not-created.sh").exists())

    def run_synthetic(self, *, drift=False, settings_drift=False, initial_fail=False):
        manifest, catalog, before, env = fixture()
        target = "ghcr.io/autoai-uteum/main-service@sha256:" + "f" * 64
        after = copy.deepcopy(before)
        after["main-service"].update(id="9" * 64, imageId="sha256:" + "f" * 64, repoDigests=[target])
        if drift:
            after["mysql"]["id"] = "8" * 64
        controller = deploy.Deployment(RELEASE_ROOT, manifest, catalog, target)
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
        self.assertIn("VERIFIED_HOST_FINGERPRINT: ${{ inputs.verified_host_fingerprint }}", text)
        self.assertIn("scripts/deploy/dev_ssh_ed25519.py", text)
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
        controller = deploy.Deployment(RELEASE_ROOT, manifest, catalog, "ghcr.io/autoai-uteum/main-service@sha256:" + "f" * 64)
        with mock.patch.object(controller, 'docker', return_value='EDUPILOT_SIGNUP_PAUSED=false\n\n'):
            self.assertEqual(controller.nginx_signup_pause('4' * 64), 'false')
        for text in ('\n', 'EDUPILOT_SIGNUP_PAUSED=false\nEDUPILOT_SIGNUP_PAUSED=true\n', 'EDUPILOT_SIGNUP_PAUSED=FALSE\n'):
            with mock.patch.object(controller, 'docker', return_value=text), self.assertRaises(ValueError):
                controller.nginx_signup_pause('4' * 64)


ssh_spec = importlib.util.spec_from_file_location('dev_ssh_ed25519', HERE / 'dev_ssh_ed25519.py')
transport = importlib.util.module_from_spec(ssh_spec)
ssh_spec.loader.exec_module(transport)


class Ed25519TransportTests(unittest.TestCase):
    def fixture(self, temp):
        manifest, catalog, _, _ = fixture()
        output = Path(temp) / 'reviewed.sh'
        deploy.prepare(RELEASE_ROOT, output, manifest, 'a' * 40, transport.PIN)
        env = {
            'VERIFIED_HOST_FINGERPRINT': transport.PIN,
            'DEV_SSH_HOST': 'dev.example.test', 'DEV_SSH_USER': 'ec2-user',
            'DEV_SSH_KEY': 'SYNTHETIC_PRIVATE_KEY_SENTINEL',
            'DEV_SSH_PASSPHRASE': 'SYNTHETIC_PASSPHRASE_SENTINEL',
            'NEW_MAIN_DIGEST': 'ghcr.io/autoai-uteum/main-service@sha256:' + 'f' * 64,
            'DEV_DEPLOY_PATH': '/srv/synthetic dev', 'GHCR_TOKEN': 'SYNTHETIC_JOB_TOKEN',
            'GHCR_USERNAME': 'fixture', 'RUNNER_TEMP': temp,
            'SSH_AUTH_SOCK': '/synthetic/agent', 'UNRELATED_SECRET': 'SYNTHETIC_UNRELATED_SECRET',
        }
        success = {'status': 'MAIN_ONLY_DEPLOYED_READBACK_PASSED', 'approvedSha': 'a' * 40,
                   'mainImageId': 'sha256:' + 'f' * 64, 'mainRepoDigest': env['NEW_MAIN_DIGEST'],
                   'mainContainerId': '9' * 64, 'excludedContainerIdsPreserved': True,
                   'mainRecreationRequested': True, 'nginxReloadRequested': True,
                   'mysqlAiNginxImagesChanged': False, 'automaticRetryOrRollback': False}
        return output.read_bytes(), env, success

    def test_only_existing_full_ed25519_public_key_is_trusted(self):
        line = transport.pinned_public_key()
        self.assertTrue(line.startswith('dev.uteum.com ssh-ed25519 '))
        self.assertEqual(transport.PIN, 'SHA256:/Z0f0DNGFvMnV+S5IaSgrv4ZJ6jwSYAQsZKyDOHfXcE')
        for value in ('ssh-rsa AAAA', 'ssh-ed25519 AAAA', 'ssh-ed25519 invalid!', line.strip() + ' extra'):
            with self.assertRaises(ValueError):
                transport.pinned_public_key(value)
        key = transport.PUBLIC_KEY.split()[1]
        with self.assertRaises(ValueError):
            transport.pinned_public_key('ssh-ed25519 ' + ('A' if key[0] != 'A' else 'B') + key[1:])
        import base64
        blob = bytearray(base64.b64decode(key)); blob[-1] ^= 1
        with self.assertRaisesRegex(ValueError, 'EXISTING_ED25519_TRUST_MUST_NOT_CHANGE'):
            transport.pinned_public_key('ssh-ed25519 ' + base64.b64encode(blob).decode())

    def test_host_user_and_option_injection_are_rejected_without_connection(self):
        with mock.patch.object(transport.subprocess, 'run', side_effect=AssertionError('NO_SSH')):
            for host in ('-oProxyCommand=bad', 'dev.example.test other', 'https://dev.example.test',
                         'dev.example.test,other.example.test', 'dev.example.test\nother', '127.0.0.1',
                         '169.254.169.254', 'dev.example.test%bad', ''):
                with self.assertRaises(ValueError, msg=host):
                    transport.target(host, 'ec2-user')
            for user in ('-oProxyCommand=bad', 'ec2-user other', 'root\nother'):
                with self.assertRaises(ValueError):
                    transport.target('dev.example.test', user)
        transport.target('dev.example.test', 'ec2-user')
        transport.target('8.8.8.8', 'ec2-user')

    def test_command_requires_exact_ed25519_strict_pinned_verification(self):
        args = transport.ssh_command('dev.example.test', 'ec2-user', '/synthetic/key', '/synthetic/known hosts')
        self.assertEqual(args[:3], ['/usr/bin/ssh', '-F', '/dev/null'])
        self.assertEqual(args[-2:], ['dev.uteum.com', 'sh -s'])
        for expected in ('HostName=dev.example.test', 'HostKeyAlias=dev.uteum.com',
                         'HostKeyAlgorithms=ssh-ed25519', 'StrictHostKeyChecking=yes', 'UpdateHostKeys=no',
                         'GlobalKnownHostsFile=/dev/null', 'KnownHostsCommand=none', 'VerifyHostKeyDNS=no',
                         'IdentityAgent=none', 'IdentitiesOnly=yes', 'ForwardAgent=no', 'ConnectionAttempts=1',
                         'PasswordAuthentication=no', 'KbdInteractiveAuthentication=no', 'ProxyCommand=none'):
            self.assertIn(expected, args)
        self.assertNotIn('HostKeyAlgorithms=+ssh-ed25519', args)
        self.assertFalse(any('accept-new' in arg or 'ssh-keyscan' in arg for arg in args))

    def test_one_attempt_scrubs_environment_and_cleans_private_files(self):
        with tempfile.TemporaryDirectory(dir=HERE, prefix='synthetic-ssh-') as temp:
            script, env, success = self.fixture(temp)
            paths = []
            def fake(args, *, input, stdout, stderr, env, timeout):
                self.assertEqual(timeout, 480)
                identity = Path(args[args.index('-i') + 1])
                known = identity.parent / 'known_hosts'
                askpass = identity.parent / 'askpass'
                paths.append(identity.parent)
                self.assertEqual(identity.read_text().strip(), 'SYNTHETIC_PRIVATE_KEY_SENTINEL')
                self.assertEqual(known.read_text(), transport.pinned_public_key())
                self.assertEqual(askpass.read_text(), transport.ASKPASS)
                if os.name == 'posix':
                    self.assertEqual(identity.stat().st_mode & 0o777, 0o600)
                    self.assertEqual(known.stat().st_mode & 0o777, 0o600)
                    self.assertEqual(askpass.stat().st_mode & 0o777, 0o700)
                self.assertEqual(set(env), {'PATH', 'LANG', 'LC_ALL', 'SSH_ASKPASS', 'SSH_ASKPASS_REQUIRE',
                                            'EDUPILOT_SSH_KEY_PASSPHRASE'})
                self.assertEqual(env['SSH_ASKPASS_REQUIRE'], 'force')
                self.assertNotIn('SYNTHETIC_JOB_TOKEN', ' '.join(args))
                self.assertNotIn('SYNTHETIC_PASSPHRASE_SENTINEL', ' '.join(args))
                self.assertNotIn('SYNTHETIC_PRIVATE_KEY_SENTINEL', input.decode())
                self.assertNotIn('SYNTHETIC_PASSPHRASE_SENTINEL', input.decode())
                self.assertTrue(input.endswith(script))
                return type('Result', (), {'stdout': json.dumps(success).encode(), 'returncode': 0})()
            with mock.patch.object(transport.subprocess, 'run', side_effect=fake) as process:
                result = transport.attempt(script, env)
            self.assertEqual(process.call_count, 1)
            self.assertTrue(result['existingServerIdentityVerified'])
            self.assertFalse(result['hostTrustChanged'])
            self.assertTrue(all(not p.exists() for p in paths))

    def test_blocked_after_main_change_preserves_remote_change_flags(self):
        with tempfile.TemporaryDirectory(dir=HERE, prefix='synthetic-ssh-') as temp:
            script, env, _ = self.fixture(temp)
            blocked = {'status': 'BLOCKED_STOP_NO_AUTOMATIC_RETRY_OR_ROLLBACK',
                       'mainRecreationRequested': True, 'nginxReloadRequested': False,
                       'automaticRetryOrRollback': False}
            response = type('Result', (), {'stdout': json.dumps(blocked).encode(), 'returncode': 2})()
            with mock.patch.object(transport.subprocess, 'run', return_value=response) as process:
                result = transport.attempt(script, env)
            self.assertEqual(process.call_count, 1)
            self.assertTrue(result['mainRecreationRequested'])
            self.assertFalse(result['nginxReloadRequested'])

    def test_auth_failure_timeout_or_host_mismatch_never_retries_and_cleans(self):
        for timeout in (False, True):
            with tempfile.TemporaryDirectory(dir=HERE, prefix='synthetic-ssh-') as temp:
                script, env, _ = self.fixture(temp)
                if timeout:
                    fault = transport.subprocess.TimeoutExpired('SYNTHETIC_SECRET', 480)
                    patch = mock.patch.object(transport.subprocess, 'run', side_effect=fault)
                else:
                    response = type('Result', (), {'stdout': b'', 'stderr': b'SYNTHETIC_SECRET', 'returncode': 255})()
                    patch = mock.patch.object(transport.subprocess, 'run', return_value=response)
                with patch as process, self.assertRaises(Exception):
                    transport.attempt(script, env)
                self.assertEqual(process.call_count, 1)
                self.assertFalse(list(Path(temp).glob('edupilot-dev-ssh-*')))

    def test_bad_pin_auth_or_payload_stops_before_any_ssh(self):
        with tempfile.TemporaryDirectory(dir=HERE, prefix='synthetic-ssh-') as temp:
            script, env, _ = self.fixture(temp)
            for key, value in (('VERIFIED_HOST_FINGERPRINT', 'SHA256:' + 'a' * 43),
                               ('DEV_SSH_HOST', '-oProxyCommand=bad'), ('DEV_SSH_KEY', ''),
                               ('GHCR_TOKEN', 'SYNTHETIC\nTOKEN'), ('RUNNER_TEMP', 'relative')):
                bad = dict(env); bad[key] = value
                with mock.patch.object(transport.subprocess, 'run', side_effect=AssertionError('NO_SSH')) as process:
                    with self.assertRaises(ValueError):
                        transport.attempt(script, bad)
                    process.assert_not_called()
            with mock.patch.object(transport.subprocess, 'run') as process, self.assertRaises(ValueError):
                transport.attempt(b'echo UNREVIEWED', env)
            process.assert_not_called()

    def test_remote_exports_are_fixed_and_shell_quoted_only_on_stdin(self):
        import shlex
        with tempfile.TemporaryDirectory(dir=HERE, prefix='synthetic-ssh-') as temp:
            script, env, _ = self.fixture(temp)
            env['DEV_DEPLOY_PATH'] = "/srv/synthetic ' $(printf injected)"
            data = transport.remote_input(script, env)
            prefix = data[:-len(script)].decode().splitlines()
            self.assertEqual(prefix[0], 'set -eu')
            self.assertEqual(len(prefix), 5)
            for line, key in zip(prefix[1:], transport.REMOTE_NAMES):
                self.assertEqual(shlex.split(line), ['export', key + '=' + env[key]])

    def test_unconfirmed_or_extra_remote_output_is_not_printable_as_result(self):
        for raw, code in ((b'SYNTHETIC_SECRET', 255), (b'{"status":"arbitrary"}', 0),
                          (b'{"status":"BLOCKED_INPUT_OR_READ_FAILED_NO_ACTION","automaticRetryOrRollback":false,"secret":"SYNTHETIC_SECRET"}', 2)):
            with self.assertRaises(ValueError):
                transport.remote_result(raw, code)

    def test_workflow_preserves_target_credentials_scope_and_ci_test_hook(self):
        text = (ROOT / '.github/workflows/deploy-dev.yml').read_text(encoding='utf-8')
        for name in ('DEV_SSH_HOST', 'DEV_SSH_USER', 'DEV_SSH_KEY', 'DEV_SSH_PASSPHRASE'):
            self.assertIn(name + ': ${{ secrets.' + name + ' }}', text)
        self.assertIn('environment: dev', text)
        self.assertNotIn('appleboy/ssh-action', text)
        self.assertNotIn('ssh-keyscan', text)
        self.assertNotIn('continue-on-error', text)
        ci = (ROOT / '.github/workflows/main-service-ci.yml').read_text(encoding='utf-8')
        self.assertIn('python3 -I -S -B scripts/deploy/test_dev_main_only.py', ci)

    def test_native_ssh_config_parser_confirms_ed25519_without_connection(self):
        executable = 'C:/Windows/System32/OpenSSH/ssh.exe' if os.name == 'nt' else '/usr/bin/ssh'
        with tempfile.TemporaryDirectory(dir=HERE, prefix='synthetic ssh config ') as temp:
            identity, known = Path(temp) / 'key', Path(temp) / 'known hosts'
            identity.write_text('SYNTHETIC_PRIVATE_KEY', encoding='ascii')
            known.write_text(transport.pinned_public_key(), encoding='ascii')
            args = transport.ssh_command('dev.example.test', 'ec2-user', identity, known)
            args[0:1] = [executable, '-G']
            args[args.index('-F') + 1] = os.devnull
            if os.name == 'nt':
                args = ['GlobalKnownHostsFile=none' if arg == 'GlobalKnownHostsFile=/dev/null' else arg for arg in args]
            result = transport.subprocess.run(args, stdin=transport.subprocess.DEVNULL,
                                              stdout=transport.subprocess.PIPE, stderr=transport.subprocess.PIPE,
                                              timeout=10)
            self.assertEqual(result.returncode, 0)
            config = dict(line.split(' ', 1) for line in result.stdout.decode().splitlines() if ' ' in line)
            self.assertEqual(config['hostname'], 'dev.example.test')
            self.assertEqual(config['hostkeyalias'], 'dev.uteum.com')
            self.assertEqual(config['hostkeyalgorithms'], 'ssh-ed25519')
            self.assertIn(config['stricthostkeychecking'], ('yes', 'true'))
            self.assertIn(config['updatehostkeys'], ('no', 'false'))
            self.assertIn(config['passwordauthentication'], ('no', 'false'))
            self.assertEqual(config['identityagent'], 'none')
            self.assertEqual(config['connectionattempts'], '1')
            self.assertIn(str(known), config['userknownhostsfile'])

    def test_native_askpass_only_supplies_existing_key_passphrase(self):
        with tempfile.TemporaryDirectory(dir=HERE, prefix='synthetic-askpass-') as temp:
            askpass = Path(temp) / 'askpass'
            askpass.write_text(transport.ASKPASS, encoding='ascii', newline='\n')
            env = dict(os.environ, EDUPILOT_SSH_KEY_PASSPHRASE='SYNTHETIC_EXISTING_PASSPHRASE')
            for prompt, permitted in (("Enter passphrase for key '/synthetic': ", True),
                                      ('Password: ', False), ('Trust new host?', False)):
                result = transport.subprocess.run([transport.sys.executable, '-I', '-S', '-B', str(askpass), prompt], env=env,
                                                  stdout=transport.subprocess.PIPE, stderr=transport.subprocess.PIPE,
                                                  timeout=10)
                self.assertEqual(result.returncode == 0, permitted)
                self.assertEqual(result.stdout, b'SYNTHETIC_EXISTING_PASSPHRASE\n' if permitted else b'')

    def test_entry_redacts_transport_errors_and_preserves_unknown_change_state(self):
        output = io.StringIO()
        with mock.patch.object(transport, 'run', side_effect=ValueError('SYNTHETIC_SECRET_HOST_KEY_OR_TOKEN')), \
                mock.patch('sys.stdout', output):
            code = transport.entry()
        self.assertEqual(code, 2)
        self.assertNotIn('SYNTHETIC_SECRET', output.getvalue())
        value = json.loads(output.getvalue())
        self.assertEqual(value['mainRecreationRequested'], 'UNKNOWN')
        self.assertEqual(value['remoteCommandStarted'], 'UNKNOWN')
        self.assertFalse(value['automaticRetryOrRollback'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
