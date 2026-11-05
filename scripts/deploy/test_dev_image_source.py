"""Synthetic source/mode checks. No Docker, registry, SSH or DB calls."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("dev_image_source", Path(__file__).with_name("dev_image_source.py"))
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)
SHA = "a" * 40


class SourceReviewTests(unittest.TestCase):
    def setUp(self):
        self.review = {"schemaVersion": 1, "approvedSha": SHA, "catalogSha256": p.base.V65_CATALOG_SHA256,
                       "ci": {"headSha": SHA, "mainRunId": 1, "aiRunId": 2, "reviewedSuccess": True},
                       "guardianMysql": {"sourceSha": "b" * 40, "catalogSha256": p.base.V65_CATALOG_SHA256,
                                         "runId": 3, "reviewedSuccess": True}}
        self.env = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/develop",
                    "GITHUB_SHA": SHA, "APPROVED_SHA": SHA, "DEV_OPERATION": "build-only",
                    "RELEASE_CONTRACT": "v65-prior-consent", "REVIEWED_SOURCE": json.dumps(self.review)}
        self.calls = []

    def git(self, args, **kwargs):
        self.calls.append(args)
        return subprocess.CompletedProcess(args, 0, (SHA + "\n").encode() if args[1] == "rev-parse" else b"", b"")

    def test_review_and_image_record_are_not_runtime_authorization(self):
        review = p.validate_dispatch(self.env, git=self.git)
        self.assertEqual(review["catalogSha256"], p.base.V65_CATALOG_SHA256)
        self.assertEqual(len(self.calls), 2)
        result = p.image_record(review, "sha256:" + "c" * 64, self.env)
        self.assertEqual(result["kind"], "BUILD_ONLY_NOT_DEPLOYMENT_AUTHORIZATION")
        self.assertEqual(result["serverActions"], 0)
        self.assertFalse(result["ciAutomaticallyQueried"])

    def test_wrong_ref_event_or_sha_fails_before_git(self):
        for key, value in [("GITHUB_REF", "refs/heads/main"), ("GITHUB_EVENT_NAME", "push"),
                           ("APPROVED_SHA", "c" * 40), ("GITHUB_SHA", "bad"), ("DEV_OPERATION", "unknown")]:
            with self.subTest(key=key):
                env = self.env | {key: value}
                with self.assertRaises(Exception): p.validate_dispatch(env, git=self.git)
        self.assertEqual(self.calls, [])

    def test_build_only_rejects_runtime_proof_or_wrong_contract(self):
        for key, value in [("REVIEWED_MANIFEST", "{}"), ("VERIFIED_HOST_FINGERPRINT", "SHA256:synthetic"),
                           ("RELEASE_CONTRACT", "v64")]:
            with self.subTest(key=key), self.assertRaises(Exception):
                p.validate_dispatch(self.env | {key: value}, git=self.git)
        self.assertEqual(self.calls, [])

    def test_ci_catalog_shape_and_positive_ids_are_strict(self):
        changes = [("approvedSha", "b" * 40), ("catalogSha256", "0" * 64), ("schemaVersion", True)]
        for key, value in changes:
            r = copy.deepcopy(self.review); r[key] = value
            with self.subTest(key=key), self.assertRaises(Exception): p.review_source(r, SHA)
        for group, key, value in [("ci", "headSha", "b" * 40), ("ci", "reviewedSuccess", "true"),
                                  ("ci", "mainRunId", True), ("guardianMysql", "runId", 0),
                                  ("guardianMysql", "catalogSha256", "0" * 64)]:
            r = copy.deepcopy(self.review); r[group][key] = value
            with self.subTest(group=group, key=key), self.assertRaises(Exception): p.review_source(r, SHA)
        r = copy.deepcopy(self.review); r["services"] = {}
        with self.assertRaises(Exception): p.review_source(r, SHA)

    def test_future_or_modified_migration_does_not_become_reviewed(self):
        parent = p.ROOT / "main-service/build/source-review-tests"; parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as directory:
            root = Path(directory); path = root / "main-service/src/main/resources/db/migration"
            shutil.copytree(p.ROOT / "main-service/src/main/resources/db/migration", path)
            (path / "V66__unreviewed.sql").write_text("SELECT 1;", encoding="utf-8")
            with self.assertRaises(Exception): p.review_source(self.review, SHA, root)
            (path / "V66__unreviewed.sql").unlink()
            migration = next(path.glob("V65__*.sql")); migration.write_text("SELECT 1;", encoding="utf-8")
            with self.assertRaises(Exception): p.review_source(self.review, SHA, root)

    def test_wrong_or_dirty_checkout_stops_before_image_work(self):
        for head, dirty in [("b" * 40, b""), (SHA, b"?? unreviewed\n")]:
            def git(args, **kwargs):
                return subprocess.CompletedProcess(args, 0, head.encode() if args[1] == "rev-parse" else dirty, b"")
            with self.assertRaises(Exception): p.validate_dispatch(self.env, git=git)

    def test_deploy_inputs_still_required_and_source_proof_is_not_a_substitute(self):
        env = self.env | {"DEV_OPERATION": "deploy", "REVIEWED_SOURCE": ""}
        with self.assertRaises(Exception): p.validate_dispatch(env, git=self.git)
        env |= {"REVIEWED_MANIFEST": "synthetic-runtime", "VERIFIED_HOST_FINGERPRINT": "synthetic-pin"}
        self.assertEqual(p.validate_dispatch(env, git=self.git), {"operation": "deploy"})
        with self.assertRaises(Exception): p.validate_dispatch(env | {"REVIEWED_SOURCE": "{}"}, git=self.git)
        self.assertEqual(self.calls, [])

    def test_image_record_requires_actual_digest_and_same_source(self):
        review = p.validate_dispatch(self.env, git=self.git)
        for digest in [None, "latest", "sha256:" + "f" * 63]:
            with self.assertRaises(Exception): p.image_record(review, digest, self.env)
        with self.assertRaises(Exception): p.image_record(review, "sha256:" + "c" * 64, self.env | {"GITHUB_SHA": "f" * 40})

    def test_workflow_separates_publisher_from_all_server_secrets_and_preserves_prepare(self):
        workflow = (p.ROOT / ".github/workflows/deploy-dev.yml").read_text(encoding="utf-8")
        image = workflow.split("  build-only:\n", 1)[1].split("\n  deploy:\n", 1)[0]
        deploy = workflow.split("\n  deploy:\n", 1)[1]
        for token in ["DEV_SSH", "DEV_DEPLOY_PATH", "dev_ssh_ed25519", "--remote", "--remove-orphans"]:
            self.assertNotIn(token, image)
        self.assertIn("inputs.operation == 'build-only'", image)
        self.assertIn("inputs.operation == 'deploy'", deploy)
        self.assertLess(deploy.index("--prepare"), deploy.index("docker/build-push-action"))
        self.assertLess(deploy.index("--prepare"), deploy.index("dev_ssh_ed25519.py"))
        self.assertIn("default: deploy", workflow)
        self.assertNotIn("\n  push:", workflow)


if __name__ == "__main__":
    unittest.main()
