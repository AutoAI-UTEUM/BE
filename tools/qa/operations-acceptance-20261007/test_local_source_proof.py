"""Synthetic parser/environment tests only; never manufactures acceptance evidence."""
import importlib.util
import os
from pathlib import Path
import tempfile
import time
import unittest

spec = importlib.util.spec_from_file_location("local_proof", Path(__file__).with_name("local-source-proof.py"))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class LocalSourceProofTest(unittest.TestCase):
    def setUp(self):
        parent = (proof.ROOT / "main-service/build/local-proof-unit").resolve()
        self.assertTrue(parent.is_relative_to(proof.ROOT))
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="composer-proof-unit-", dir=parent)
        self.root = Path(self.temp.name).resolve()
        self.assertTrue(self.root.is_relative_to(parent))
        self.addCleanup(self.temp.cleanup)

    def reports(self):
        for index, name in enumerate(proof.REQUIRED):
            case = proof.COMPATIBILITY if name.endswith(".ComposerJpaTest") else "syntheticCase()"
            (self.root / f"TEST-{index}.xml").write_text(
                f'<testsuite name="{name}" tests="1" failures="0" errors="0" skipped="0">'
                f'<testcase name="{case}"/></testsuite>', encoding="utf-8")

    def test_windows_system_keys_are_case_insensitive_and_credentials_are_excluded(self):
        env = proof.isolated_env({"SYSTEMROOT": "synthetic-windows", "Path": "synthetic-path", "COMSPEC": "synthetic-cmd",
            "AWS_ACCESS_KEY_ID": "synthetic-secret", "EDUPILOT_AI_INTERNAL_TOKEN": "synthetic-secret",
            "JAVA_TOOL_OPTIONS": "synthetic-override", "TURN_COMPLETION_MYSQL_URL": "synthetic-db"}, "jdk", "cache")
        self.assertEqual(env["SYSTEMROOT"], "synthetic-windows")
        self.assertEqual(env["Path"], "synthetic-path")
        self.assertEqual(env["COMSPEC"], "synthetic-cmd")
        self.assertEqual(set(env), {"SYSTEMROOT", "Path", "COMSPEC", "JAVA_HOME", "GRADLE_USER_HOME"})

    def test_fresh_complete_synthetic_junit_is_parsed_without_claiming_execution(self):
        self.reports()
        summary = proof.junit_summary(self.root, time.time() - 1)
        self.assertEqual(summary["totals"]["tests"], len(proof.REQUIRED))
        self.assertEqual(summary["totals"]["passed"], len(proof.REQUIRED))
        self.assertEqual(summary["kind"], "LOCAL_JUNIT_METADATA")

    def test_no_reports_cannot_be_success(self):
        with self.assertRaisesRegex(ValueError, "No real"):
            proof.junit_summary(self.root, time.time())

    def test_stale_reports_are_rejected(self):
        self.reports()
        for path in self.root.glob("*.xml"):
            os.utime(path, (1, 1))
        with self.assertRaisesRegex(ValueError, "Stale"):
            proof.junit_summary(self.root, time.time())

    def test_failed_cases_and_counter_mismatches_are_rejected(self):
        self.reports()
        path = self.root / "TEST-0.xml"
        text = path.read_text()
        path.write_text(text.replace('/></testsuite>', '><failure/></testcase></testsuite>'))
        with self.assertRaisesRegex(ValueError, "counter mismatch"):
            proof.junit_summary(self.root, time.time() - 1)
        path.write_text(path.read_text().replace('failures="0"', 'failures="1"'))
        with self.assertRaisesRegex(ValueError, "Failed"):
            proof.junit_summary(self.root, time.time() - 1)

    def test_missing_or_skipped_required_suite_is_rejected(self):
        self.reports()
        path = self.root / "TEST-0.xml"
        path.write_text(path.read_text().replace('skipped="0"', 'skipped="1"')
            .replace('/></testsuite>', '><skipped/></testcase></testsuite>'))
        with self.assertRaisesRegex(ValueError, "Required suite"):
            proof.junit_summary(self.root, time.time() - 1)
        path.unlink()
        with self.assertRaisesRegex(ValueError, "Required suite"):
            proof.junit_summary(self.root, time.time() - 1)

    def test_compatibility_case_must_actually_be_present(self):
        self.reports()
        for path in self.root.glob("*.xml"):
            path.write_text(path.read_text().replace(proof.COMPATIBILITY, "unrelatedCase()"))
        with self.assertRaisesRegex(ValueError, "compatibility"):
            proof.junit_summary(self.root, time.time() - 1)

    def test_snapshot_detects_changes_and_normalizes_text_only(self):
        for name in proof.BUILD_FILES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"synthetic\r\n")
        source = self.root / "main-service/src/main/java/Synthetic.java"
        source.parent.mkdir(parents=True)
        source.write_bytes(b"synthetic\r\n")
        (self.root / "main-service/src/test").mkdir(parents=True)
        first = proof.snapshot(self.root)
        source.write_bytes(b"synthetic\n")
        self.assertEqual(proof.snapshot(self.root), first)
        source.write_bytes(b"changed\n")
        self.assertNotEqual(proof.snapshot(self.root)["sha256"], first["sha256"])
        jar = self.root / "main-service/gradle/wrapper/gradle-wrapper.jar"
        before = proof.snapshot(self.root)
        jar.write_bytes(b"synthetic\n")
        self.assertNotEqual(proof.snapshot(self.root)["sha256"], before["sha256"])


if __name__ == "__main__":
    unittest.main()
