"""Offline source-contract checks; actual GitHub event execution needs a PR run."""
import ast
import fnmatch
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ".github/workflows/guardian-mysql-ci.yml"


class GuardianMysqlWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / WORKFLOW).read_text(encoding="utf-8")
        # Deliberately accept only this workflow's explicit list form. A syntax
        # change requires review; this is not a general YAML or Actions parser.
        cls.events = re.search(r"(?ms)^on:\n(.*?)(?=^\S|\Z)", cls.workflow).group(1)
        branch_list = re.search(r"^    branches: \[([^\]\n]+)\]$", cls.events, re.M)
        cls.branches = [value.strip() for value in branch_list.group(1).split(",")]
        cls.paths = [ast.literal_eval(value) for value in
                     re.findall(r"^      - (.+)$", cls.events, re.M)]
        cls.job = re.search(r"(?ms)^  guardian-mysql:\n(.*?)(?=^  [\w-]+:|\Z)", cls.workflow).group(1)

    def scheduled(self, event, base, changed_paths):
        return (event == "pull_request" and base in self.branches
                and any(fnmatch.fnmatchcase(path, pattern)
                        for path in changed_paths for pattern in self.paths))

    def test_develop_and_main_prs_cover_every_relevant_path(self):
        for base in ("develop", "main"):
            for path in (WORKFLOW, "scripts/qa/guardian-mysql/run.sh",
                         "scripts/qa/guardian-mysql/report.py",
                         "main-service/build.gradle",
                         "main-service/src/main/resources/db/migration/V65__example.sql"):
                with self.subTest(base=base, path=path):
                    self.assertTrue(self.scheduled("pull_request", base, [path]))

    def test_other_events_bases_and_unrelated_paths_do_not_start_mysql(self):
        self.assertEqual(re.findall(r"^  (\w+):", self.events, re.M), ["pull_request"])
        self.assertEqual(re.findall(r"^    (\w+):", self.events, re.M), ["branches", "paths"])
        self.assertEqual(set(self.branches), {"develop", "main"})
        self.assertEqual(self.paths, [WORKFLOW, "scripts/qa/guardian-mysql/**", "main-service/**"])
        for event, base, paths in (
            ("push", "main", ["main-service/build.gradle"]),
            ("workflow_dispatch", "main", [WORKFLOW]),
            ("pull_request", "feature/example", [WORKFLOW]),
            ("pull_request", "main", ["docs/example.md", "ai-service/demo.py"]),
        ):
            with self.subTest(event=event, base=base, paths=paths):
                self.assertFalse(self.scheduled(event, base, paths))

    def test_same_repository_and_read_only_checkout_guards_remain(self):
        self.assertEqual(re.findall(r"^    if: (.+)$", self.job, re.M),
                         ["github.event.pull_request.head.repo.full_name == github.repository"])
        self.assertRegex(self.workflow, r"(?m)^permissions:\n  contents: read\n")
        self.assertEqual(re.findall(r"^[ \t]*permissions:", self.workflow, re.M), ["permissions:"])
        self.assertNotRegex(self.workflow, r"(?m)^  \S+: write$")
        self.assertIn("persist-credentials: false", self.workflow)
        self.assertNotIn("secrets.", self.workflow)

    def test_hosted_runner_and_disposable_database_isolation_remain(self):
        self.assertIn("runs-on: ubuntu-24.04", self.workflow)
        self.assertIn("image: mysql:8.4.11", self.workflow)
        self.assertIn("--tmpfs /var/lib/mysql:rw,size=1073741824", self.workflow)
        self.assertNotRegex(self.workflow, r"(?m)^\s+(ports|volumes):")
        script = (ROOT / "scripts/qa/guardian-mysql/run.sh").read_text(encoding="utf-8")
        for guard in ('test "${GITHUB_ACTIONS:-}" = true', 'test "${RUNNER_OS:-}" = Linux',
                      'test "${RUNNER_ENVIRONMENT:-}" = github-hosted',
                      'docker network create --internal "$network"',
                      'sh ./gradlew --offline --no-daemon --max-workers=2 guardianMysqlTest'):
            self.assertIn(guard, script)

    def test_source_contract_check_is_in_main_ci_without_a_path_condition(self):
        ci = (ROOT / ".github/workflows/main-service-ci.yml").read_text(encoding="utf-8")
        events = re.search(r"(?ms)^on:\n(.*?)(?=^\S|\Z)", ci).group(1)
        pr_filters = re.search(r"(?ms)^  pull_request:\n(.*?)(?=^  \S|\Z)", events).group(1)
        self.assertEqual(pr_filters.strip(), "")
        job_header = re.search(r"(?ms)^  main-service-build:\n(.*?)^    steps:", ci).group(1)
        self.assertNotRegex(job_header, r"(?m)^    if:")
        self.assertIn("      - name: Validate Guardian MySQL workflow triggers without operations\n"
                      "        run: python3 -I -S -B scripts/qa/guardian-mysql/test_workflow.py", ci)
        step = re.search(r"(?ms)^      - name: Validate Guardian MySQL workflow triggers without operations\n(.*?)(?=^      -|\Z)", ci).group(1)
        self.assertNotRegex(step, r"(?m)^        if:")


if __name__ == "__main__":
    unittest.main(verbosity=2)
