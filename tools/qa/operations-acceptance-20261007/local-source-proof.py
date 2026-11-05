"""Run an offline, synthetic full build and derive an unsigned LOCAL source receipt.

No existing acceptance evidence is edited. No Docker, remote API, deployment, mail,
real DB, provider credentials, or CI attestation is requested. Replays need a local
JDK 21 and an already populated, explicitly selected workspace Gradle cache.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
FEATURE = "main-service/src/main/java/io/edupilot/aiusage/AiFeature.java"
SCOPE = "composer-spring-local-synthetic;not-ci;not-live-acceptance"
TEXT = {".java", ".gradle", ".bat", ".properties", ".yml", ".yaml", ".xml", ".json", ".sql", ".txt", ".md"}
BUILD_FILES = [f"main-service/{p}" for p in ["build.gradle", "settings.gradle", "gradlew", "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"]]
REQUIRED = ["io.edupilot.MainServiceApplicationTests"] + [f"io.edupilot.composer.{name}" for name in [
    "ComposerAdmissionServiceTest", "ComposerJpaTest", "ComposerPredictionControllerTest",
    "ComposerPredictionServiceTest", "ComposerSecurityJpaTest", "ComposerSnapshotServiceTest", "HttpComposerAiClientTest"]]
COMPATIBILITY = "legacyFeatureValuesAndPredictionRoundTripThroughVarcharAndAdminJson()"
KEYS = ["tests", "passed", "failures", "errors", "skipped"]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def utc():
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def snapshot(root):
    paths = [root / p for p in BUILD_FILES]
    for source in [root / "main-service/src/main", root / "main-service/src/test"]:
        for path in source.rglob("*"):
            if path.is_symlink():
                raise ValueError("Symlink source input")
            if path.is_file():
                paths.append(path)
    rows = []
    for path in sorted(paths, key=lambda p: p.relative_to(root).as_posix()):
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError("Source outside repository")
        data = path.read_bytes()
        if path.suffix in TEXT or path.name == "gradlew":
            data = data.replace(b"\r\n", b"\n")
        rows.append({"path": path.relative_to(root).as_posix(), "sha256": digest(data)})
    tree = digest("".join(f"{x['path']}\0{x['sha256']}\n" for x in rows).encode())
    return {"files": rows, "count": len(rows), "sha256": tree}


def junit_summary(directory, started):
    suites = []
    paths = sorted(directory.glob("TEST-*.xml"))
    if not paths:
        raise ValueError("No real JUnit reports")
    for path in paths:
        if path.stat().st_mtime < started - 2:
            raise ValueError("Stale JUnit report")
        raw = path.read_bytes()
        xml = ET.fromstring(raw)
        cases = []
        for case in xml.findall("testcase"):
            status = "FAILED" if case.find("failure") is not None else "ERROR" if case.find("error") is not None else "SKIPPED" if case.find("skipped") is not None else "PASSED"
            cases.append({"name": case.attrib["name"], "status": status})
        counts = {"tests": len(cases), **{key: sum(x["status"] == status for x in cases) for key, status in [
            ("passed", "PASSED"), ("failures", "FAILED"), ("errors", "ERROR"), ("skipped", "SKIPPED")]}}
        if any(int(xml.attrib.get(key, "0")) != counts[key] for key in ["tests", "failures", "errors", "skipped"]):
            raise ValueError("JUnit counter mismatch")
        suites.append({"name": xml.attrib["name"], **counts, "rawJUnitSha256": digest(raw), "cases": cases})
    if len({x["name"] for x in suites}) != len(suites):
        raise ValueError("Duplicate JUnit suite")
    totals = {key: sum(x[key] for x in suites) for key in KEYS}
    if totals["tests"] == 0 or totals["failures"] or totals["errors"]:
        raise ValueError("Failed or empty build")
    for name in REQUIRED:
        suite = next((x for x in suites if x["name"] == name), None)
        if not suite or suite["passed"] == 0 or suite["passed"] != suite["tests"]:
            raise ValueError("Required suite not passed")
    compatibility = next(x for x in suites if x["name"] == "io.edupilot.composer.ComposerJpaTest")
    if not any(x["name"] == COMPATIBILITY and x["status"] == "PASSED" for x in compatibility["cases"]):
        raise ValueError("Feature compatibility not tested")
    return {"schemaVersion": 1, "kind": "LOCAL_JUNIT_METADATA", "totals": totals, "suites": suites}


def write_new(path, data):
    payload = (json.dumps(data, indent=2, ensure_ascii=True) + "\n").encode()
    with path.open("xb") as stream:
        stream.write(payload)
    return digest(payload)


def isolated_env(source, java_home, cache):
    # os.environ uppercases names on Windows. Preserve essential system keys
    # case-insensitively; dropping SYSTEMROOT breaks the Windows socket provider.
    allowed = {key.upper() for key in ["PATH", "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP", "USERPROFILE",
        "HOME", "APPDATA", "LOCALAPPDATA", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE"]}
    env = {key: value for key, value in source.items() if key.upper() in allowed}
    if os.name == "nt" and not any(key.upper() == "SYSTEMROOT" for key in env):
        raise ValueError("Windows system environment missing")
    env.update(JAVA_HOME=str(java_home), GRADLE_USER_HOME=str(cache))
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--gradle-user-home", type=Path, required=True)
    args = parser.parse_args()
    java_home = args.java_home.resolve()
    cache = args.gradle_user_home.resolve()
    if not cache.is_relative_to(ROOT.parent) or cache == ROOT.parent:
        raise ValueError("Use an existing workspace Gradle cache")
    # The wrapper can download its distribution even when Gradle uses --offline.
    # Refuse to start unless that exact public distribution is already cached.
    properties = (ROOT / "main-service/gradle/wrapper/gradle-wrapper.properties").read_text()
    match = re.search(r"gradle-(\d+(?:\.\d+)+)-bin\.zip", properties)
    if not match or not list((cache / "wrapper/dists" / f"gradle-{match[1]}-bin").glob("*/gradle-*/bin/gradle")):
        raise ValueError("Required distribution is not cached; no download attempted")
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.is_file():
        raise ValueError("JDK executable missing")
    env = isolated_env(os.environ, java_home, cache)
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True, env=env, check=True)
    if not re.search(r'version "21[.\"]', version.stdout + version.stderr):
        raise ValueError("JDK 21 required")
    before = snapshot(ROOT)
    generator_hash = digest(Path(__file__).read_bytes().replace(b"\r\n", b"\n"))
    base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, env=env, text=True).strip()
    if not re.fullmatch(r"[a-f0-9]{40}", base):
        raise ValueError("Invalid base commit")
    run_id = dt.datetime.now(dt.timezone.utc).strftime("composer-%Y%m%dT%H%M%SZ")
    output = ROOT / "main-service/build/local-proof" / run_id
    output.mkdir(parents=True, exist_ok=False)
    env.update(AWS_EC2_METADATA_DISABLED="true", AWS_SHARED_CREDENTIALS_FILE=str(output / "absent-credentials"),
        AWS_CONFIG_FILE=str(output / "absent-config"))
    previous = output / "previous-junit"
    previous.mkdir()
    for old in (ROOT / "main-service/build/test-results/test").glob("TEST-*.xml"):
        shutil.copy2(old, previous / old.name)
    write_new(output / "source-inputs.json", before)
    started_at, started = utc(), time.time()
    log = output / "gradle.log"
    command = [str(java), "-Xmx64m", "-Xms64m", "-Dorg.gradle.appname=gradlew", "-classpath",
        str(ROOT / "main-service/gradle/wrapper/gradle-wrapper.jar"), "org.gradle.wrapper.GradleWrapperMain",
        "build", "--offline", "--no-daemon", "--rerun-tasks", "--max-workers=2", "--console=plain",
        "-Dit.ai=false", "-Dorg.gradle.java.installations.auto-detect=false",
        f"-Dorg.gradle.java.installations.paths={java_home}"]
    print(f"LOCAL_PROOF RUNNING_TESTS {run_id} inputs={before['count']}", flush=True)
    with log.open("xb") as stream:
        completed = subprocess.run(command, cwd=ROOT / "main-service", env=env, stdout=stream, stderr=subprocess.STDOUT)
    finished_at = utc()
    if completed.returncode:
        print(f"LOCAL_PROOF BUILD_FAILED exit={completed.returncode}; receipt not created", flush=True)
        return completed.returncode
    after = snapshot(ROOT)
    if before != after or generator_hash != digest(Path(__file__).read_bytes().replace(b"\r\n", b"\n")):
        raise ValueError("Source changed during test run; receipt not created")
    summary = junit_summary(ROOT / "main-service/build/test-results/test", started)
    summary_hash = write_new(output / "composer-spring-20261104.tests.json", summary)
    evidence = json.loads((ROOT / "tools/qa/operations-acceptance-20261007/evidence-map.json").read_text(encoding="utf-8"))
    historical = next(x for bundle in evidence["bundles"] for x in bundle["sources"] if x["path"] == FEATURE)
    feature = next(x for x in before["files"] if x["path"] == FEATURE)
    receipt = {"schemaVersion": 1, "kind": "LOCAL_WORKTREE_TESTS", "scope": SCOPE,
        "generatedAtUtc": utc(), "ciRunUrl": None, "executionAuthorized": False, "operatorAcceptance": False,
        "realMysql": False, "realProvider": False, "deployedServices": False,
        "source": {"kind": "WORKTREE_SNAPSHOT", "baseCommit": base,
            "inputCount": before["count"], "inputTreeSha256": before["sha256"]},
        "binding": {"path": FEATURE, "baselineSha256": historical["sha256"], "sha256": feature["sha256"]},
        "generatorSha256": generator_hash, "summarySha256": summary_hash,
        "logSha256": digest(log.read_bytes()), "totals": summary["totals"],
        "execution": {"profile": "main-offline-full-build-v1", "javaMajor": 21, "exitCode": 0,
            "rerunTasks": True, "startedAtUtc": started_at, "finishedAtUtc": finished_at},
        "attestation": "UNSIGNED_LOCAL_REPLAYABLE;not-CI-or-live-acceptance"}
    receipt_hash = write_new(output / "composer-spring-20261104.receipt.json", receipt)
    print(json.dumps({"status": "LOCAL_PROOF_PASSED", "runId": run_id, "totals": summary["totals"],
        "inputTreeSha256": before["sha256"], "receiptSha256": receipt_hash, "summarySha256": summary_hash}), flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        # Avoid echoing environment values, arbitrary XML, private paths or provider content.
        print(f"LOCAL_PROOF FAILED type={type(error).__name__}; no acceptance or execution authorized", file=sys.stderr)
        sys.exit(2)
