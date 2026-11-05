"""Source-only DEV Main image review. Never authenticates, contacts a server or deploys."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

_spec = importlib.util.spec_from_file_location("dev_main_only", Path(__file__).with_name("dev_main_only.py"))
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)

ROOT = Path(__file__).resolve().parents[2]


def require(value, code):
    base.require(value, code)


def positive(value):
    return type(value) is int and value > 0


def review_source(value, sha, root=ROOT):
    base.shape(value, ("schemaVersion", "approvedSha", "catalogSha256", "ci", "guardianMysql"))
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1, "SOURCE_SCHEMA_REQUIRED")
    require(base.is_hex(sha, r"[a-f0-9]{40}") and value["approvedSha"] == sha, "EXACT_SOURCE_SHA_REQUIRED")
    catalog = base.source_catalog_v65(root)
    digest = base.sha_json(catalog)
    require(value["catalogSha256"] == digest, "REVIEWED_SOURCE_CATALOG_REQUIRED")
    ci = value["ci"]
    base.shape(ci, ("headSha", "mainRunId", "aiRunId", "reviewedSuccess"))
    require(ci["headSha"] == sha and ci["reviewedSuccess"] is True
            and positive(ci["mainRunId"]) and positive(ci["aiRunId"]), "EXACT_REVIEWED_MAIN_AI_CI_REQUIRED")
    mysql = value["guardianMysql"]
    base.shape(mysql, ("sourceSha", "catalogSha256", "runId", "reviewedSuccess"))
    require(base.is_hex(mysql["sourceSha"], r"[a-f0-9]{40}") and mysql["catalogSha256"] == digest
            and positive(mysql["runId"]) and mysql["reviewedSuccess"] is True, "REVIEWED_GUARDIAN_CATALOG_REQUIRED")
    return digest


def validate_dispatch(env, root=ROOT, git=subprocess.run):
    operation = env.get("DEV_OPERATION", "deploy")
    require(operation in ("deploy", "build-only"), "EXPLICIT_OPERATION_REQUIRED")
    sha = env.get("GITHUB_SHA", "")
    require(env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and env.get("GITHUB_REF") == "refs/heads/develop"
            and env.get("APPROVED_SHA") == sha and base.is_hex(sha, r"[a-f0-9]{40}"), "EXACT_DEVELOP_DISPATCH_REQUIRED")
    if operation == "deploy":
        # The original --prepare validator still validates the complete runtime contract
        # before its image build or SSH step. Optional dispatch inputs are NOT optional here.
        require(bool(env.get("REVIEWED_MANIFEST", "").strip())
                and bool(env.get("VERIFIED_HOST_FINGERPRINT", "").strip()), "DEPLOY_RUNTIME_INPUTS_REQUIRED")
        require(not env.get("REVIEWED_SOURCE", "").strip(), "SOURCE_REVIEW_IS_NOT_RUNTIME_AUTHORIZATION")
        return {"operation": operation}
    require(env.get("RELEASE_CONTRACT") == base.V65_CONTRACT, "EXPLICIT_V65_IMAGE_CONTRACT_REQUIRED")
    require(not env.get("REVIEWED_MANIFEST", "").strip()
            and not env.get("VERIFIED_HOST_FINGERPRINT", "").strip(), "BUILD_ONLY_MUST_NOT_ACCEPT_RUNTIME_EVIDENCE")
    value = json.loads(env.get("REVIEWED_SOURCE", ""))
    catalog = review_source(value, sha, root)
    head = git(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, check=True).stdout.decode().strip()
    dirty = git(["git", "status", "--porcelain", "--untracked-files=all"], cwd=root, capture_output=True, check=True).stdout
    require(head == sha and not dirty, "CLEAN_EXACT_SOURCE_REQUIRED")
    return {"operation": operation, "approvedSha": sha, "catalogSha256": catalog,
            "sourceReviewSha256": base.sha_json(value), "ci": value["ci"], "guardianMysql": value["guardianMysql"]}


def image_record(review, digest, env):
    require(review.get("operation") == "build-only", "SOURCE_REVIEW_REQUIRED")
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", digest or "") is not None, "ACTUAL_IMAGE_DIGEST_REQUIRED")
    require(review["approvedSha"] == env.get("GITHUB_SHA"), "SOURCE_CHANGED_BEFORE_IMAGE_RECORD")
    return {"kind": "BUILD_ONLY_NOT_DEPLOYMENT_AUTHORIZATION", **review,
            "repoDigest": "ghcr.io/autoai-uteum/main-service@" + digest,
            "workflowRunId": env.get("GITHUB_RUN_ID"), "serverActions": 0,
            "ciAutomaticallyQueried": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image-digest")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = validate_dispatch(os.environ)
        if args.image_digest:
            result = image_record(result, args.image_digest, os.environ)
            require(args.output is not None and not args.output.exists(), "NEW_OUTPUT_REQUIRED")
            with args.output.open("x", encoding="utf-8") as stream:
                json.dump(result, stream, sort_keys=True)
                stream.write("\n")
        else:
            if result["operation"] == "build-only":
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
                    stream.write("catalog_sha256=" + result["catalogSha256"] + "\n")
        print(json.dumps({"status": "SOURCE_REVIEWED_NO_SERVER_ACTION", "operation": result["operation"], "serverActions": 0}))
        return 0
    except Exception:
        # Never echo arbitrary input, environment, stderr or exception payloads.
        print(json.dumps({"status": "SOURCE_REVIEW_BLOCKED_NO_ACTION", "serverActions": 0}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
