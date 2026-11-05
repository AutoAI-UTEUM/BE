"""Inspect both local JAR entry points and invoke the operator default (no action, no DB)."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def start_class(path):
    with zipfile.ZipFile(path) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").replace("\r\n", "\n")
    return next(row.split(": ", 1)[1] for row in manifest.splitlines() if row.startswith("Start-Class: "))


def verify(java):
    operator = ROOT / "main-service/build/operator/deletion-ledger.jar"
    applications = [p for p in (ROOT / "main-service/build/libs").glob("*.jar") if not p.name.endswith("-plain.jar")]
    if len(applications) != 1 or start_class(applications[0]) != "io.edupilot.MainServiceApplication":
        raise ValueError("MAIN_APPLICATION_ENTRY_POINT_CHANGED")
    if start_class(operator) != "io.edupilotops.deletion.DeletionLedgerCli":
        raise ValueError("OPERATOR_ENTRY_POINT_MISSING")
    env = {k: v for k, v in os.environ.items() if k.upper() in ("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "JAVA_HOME")}
    result = subprocess.run([java, "-jar", str(operator)], env=env, capture_output=True, timeout=45, check=False)
    if result.returncode != 0 or json.loads(result.stdout) != {"status": "OPERATOR_WRAPPER_REQUIRED_NO_ACTION"}:
        raise ValueError("PACKAGED_OPERATOR_DEFAULT_MUST_NOT_START_APPLICATION")
    rejected = subprocess.run([java, "-jar", str(operator), "export", "--execute"], env=env, capture_output=True, timeout=45, check=False)
    if rejected.returncode != 2 or json.loads(rejected.stdout) != {"status": "OPERATOR_LEDGER_FAILED_NO_RETRY_OR_REOPEN"}:
        raise ValueError("PACKAGED_OPERATOR_MUST_REJECT_EXECUTION_WITHOUT_WRAPPER")
    return {"status": "OPERATOR_JAR_DEFAULT_NO_ACTION_PASSED", "checksPassed": 2, "serverActions": 0, "databaseConnectionsRequested": 0}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--java", default="java")
    args = parser.parse_args()
    print(json.dumps(verify(args.java), sort_keys=True))
