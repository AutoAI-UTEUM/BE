"""Validate real JUnit cases and preserve the exact XML in free Actions logs, without artifacts/caches."""
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zlib

EXPECTED = {
    "GuardianConsentFenceMysqlTest": 14,
    "GuardianAdminLockMysqlTest": 22,
    "GuardianTeamFullMigrationTest": 1,
    "TurnPersistenceConsentMysqlTest": 2,
}
H2_METHOD = "accessMigrationPreservesExistingEvidenceAndRequiresExplicitPolicyForNewApproval"


def outcome(case):
    if case.find("failure") is not None:
        return "failure"
    if case.find("error") is not None:
        return "error"
    skipped = case.find("skipped")
    if skipped is not None:
        return "abort" if "TestAbortedException" in ET.tostring(skipped, encoding="unicode") else "skip"
    return "success"


def main():
    results = {name: Counter() for name in EXPECTED}
    invocations = {name: [] for name in EXPECTED}
    h2 = Counter()
    unique = set()
    violations = []
    files = sorted(Path("main-service/build/test-results/guardianMysqlTest").glob("TEST-*.xml"))
    # Preserve every available file even when a JVM crash leaves only partial results.
    for path in files:
        data = path.read_bytes()
        # Exact original XML is recoverable and SHA-256 verifiable, including failure/error details.
        encoded = base64.b64encode(zlib.compress(data)).decode("ascii")
        print(f"GUARDIAN_JUNIT_BEGIN {path.name} {hashlib.sha256(data).hexdigest()} {len(data)}")
        for start in range(0, len(encoded), 1024):
            print("GUARDIAN_JUNIT_DATA " + encoded[start:start + 1024])
        print("GUARDIAN_JUNIT_END")
    for path in files:
        try:
            suite = ET.fromstring(path.read_bytes())
        except ET.ParseError as error:
            violations.append(f"Malformed XML: {path.name}: {error}")
            continue
        cases = suite.findall("testcase")
        if len(cases) != int(suite.attrib["tests"]):
            violations.append(f"JUnit count mismatch: {path.name}")
        for case in cases:
            name = case.attrib["name"]
            class_name = case.attrib["classname"].rsplit(".", 1)[-1]
            key = (class_name, name)
            if key in unique:
                violations.append(f"Duplicate invocation: {key}")
            unique.add(key)
            if class_name not in EXPECTED:
                violations.append(f"Unexpected class: {class_name}")
                continue
            if class_name == "GuardianTeamFullMigrationTest" and name.startswith(H2_METHOD + "("):
                h2[outcome(case)] += 1
            else:
                results[class_name][outcome(case)] += 1
                invocations[class_name].append(name)
    totals = sum(results.values(), Counter())
    summary = {
        "mysql": {status: totals[status] for status in ("success", "failure", "error", "skip", "abort")},
        "suites": {name: dict(counts) for name, counts in results.items()},
        "h2": {status: h2[status] for status in ("success", "failure", "error", "skip", "abort")},
        "invocations": invocations,
        "missing_suites": [name for name, counts in results.items() if not counts],
        "violations": violations,
    }
    print("GUARDIAN_JUNIT_SUMMARY " + json.dumps(summary, sort_keys=True))
    assert len(files) == 4, f"Expected four real JUnit XML files, got {len(files)}"
    assert not violations, violations
    for name, expected in EXPECTED.items():
        assert results[name] == Counter(success=expected), f"Incomplete/failing MySQL suite: {name} {results[name]}"
    assert totals == Counter(success=39)
    assert h2 == Counter(success=1), f"H2 must be reported separately: {h2}"
    return 0


if __name__ == "__main__":
    sys.exit(main())
