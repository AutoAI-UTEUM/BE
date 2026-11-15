"""Verify the pinned catalog and its fail-closed handling of incompatible edits."""

import shutil
import tempfile
import unittest
from pathlib import Path

from dev_main_only import source_catalog, source_catalog_v65
from dev_v66_source_preflight import MIGRATIONS, V66_NAME, preflight


class V66SourcePreflightTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.directory = self.root / MIGRATIONS
        shutil.copytree(Path(__file__).resolve().parents[2] / MIGRATIONS, self.directory)
        self.v66 = self.directory / V66_NAME

    def test_exact_candidate_is_source_only_and_old_guards_still_refuse(self):
        self.assertEqual(preflight(self.root)["status"], "SOURCE_ONLY_NOT_DEPLOYMENT_AUTHORIZATION")
        for old in (source_catalog, source_catalog_v65):
            with self.assertRaises(ValueError):
                old(self.root)

    def test_missing_and_future_versions_are_rejected(self):
        data = self.v66.read_bytes()
        self.v66.unlink()
        with self.assertRaisesRegex(ValueError, "EXACT_V1_THROUGH_V66_REQUIRED"):
            preflight(self.root)
        self.v66.write_bytes(data)
        (self.directory / "V67__future.sql").write_text("SELECT 1;")
        with self.assertRaisesRegex(ValueError, "EXACT_V1_THROUGH_V66_REQUIRED"):
            preflight(self.root)

    def test_altered_v65_prefix_is_rejected(self):
        v65 = next(self.directory.glob("V65__*.sql"))
        v65.write_bytes(v65.read_bytes() + b"\n-- unexpected edit\n")
        with self.assertRaisesRegex(ValueError, "REVIEWED_V65_PREFIX_REQUIRED"):
            preflight(self.root)

    def test_different_v66_filename_is_rejected(self):
        self.v66.rename(self.directory / "V66__unreviewed.sql")
        with self.assertRaisesRegex(ValueError, "EXACT_V66_MIGRATION_FILENAME_REQUIRED"):
            preflight(self.root)

    def test_changed_v66_even_with_same_flyway_checksum_is_rejected(self):
        self.v66.write_bytes(self.v66.read_bytes() + b"\n\n")
        with self.assertRaisesRegex(ValueError, "EXACT_V66_MIGRATION_CONTENT_REQUIRED"):
            preflight(self.root)


if __name__ == "__main__":
    unittest.main()
