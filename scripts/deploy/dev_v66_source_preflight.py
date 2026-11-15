"""Source-only DEV V66 validation. Never builds, publishes or contacts a server."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

_spec = importlib.util.spec_from_file_location("dev_main_only", Path(__file__).with_name("dev_main_only.py"))
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)
V65_CATALOG_SHA256 = base.V65_CATALOG_SHA256

V66_NAME = "V66__learning_quiz_drafts.sql"
V66_FILE_SHA256 = "c7bea90ab2bc460944fa2276bb98adcfe1796a398f4282b3fa2aa7e59ec81bb1"
V66_CATALOG_SHA256 = "da019c98b93a1516d2357c72e86ec2ed753f3c71934de375a6cf8e144d674ce1"
MIGRATIONS = "main-service/src/main/resources/db/migration"


def preflight(root):
    directory = root / MIGRATIONS
    catalog = base.read_source_catalog(root)
    base.require(set(catalog) == {str(v) for v in range(1, 67)}, "EXACT_V1_THROUGH_V66_REQUIRED")
    baseline = {k: v for k, v in catalog.items() if k != "66"}
    base.require(base.sha_json(baseline) == V65_CATALOG_SHA256, "REVIEWED_V65_PREFIX_REQUIRED")
    file = directory / V66_NAME
    base.require(file.is_file() and not file.is_symlink(), "EXACT_V66_MIGRATION_FILENAME_REQUIRED")
    base.require(hashlib.sha256(file.read_bytes()).hexdigest() == V66_FILE_SHA256
            and base.sha_json(catalog) == V66_CATALOG_SHA256, "EXACT_V66_MIGRATION_CONTENT_REQUIRED")
    return {"status": "SOURCE_ONLY_NOT_DEPLOYMENT_AUTHORIZATION",
            "target": "DEV", "baselineCatalogSha256": V65_CATALOG_SHA256,
            "candidateCatalogSha256": V66_CATALOG_SHA256, "v66FlywayChecksum": catalog["66"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=Path(__file__).resolve().parents[2], type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(preflight(args.root), sort_keys=True))
    except (OSError, ValueError, TypeError) as exc:
        parser.exit(2, f"DEV V66 source preflight refused: {exc}\n")


if __name__ == "__main__":
    main()
