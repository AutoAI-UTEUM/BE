"""python -m tests.benchmarks: offline preparation/check, explicit budgeted live run."""

import argparse
import asyncio
import contextlib
import hashlib
import json
import os
import shutil
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from tests.benchmarks.common import (
    BenchmarkError,
    RequestBudget,
    digest,
    make_output,
    read_json,
    write_json,
)
from tests.benchmarks.report import report_markdown, review_template, summarize_metrics
from tests.benchmarks.scenarios import build_suite, expected_calls, schedule, validate_suite
from tests.benchmarks.worker import CONFIG_FIELDS

HARNESS_ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = HARNESS_ROOT.parent


def git(repository: Path, *args: str) -> str:
    executable = shutil.which("git")
    if executable is None:
        raise BenchmarkError("GIT_NOT_FOUND")
    return subprocess.check_output(  # noqa: S603 — argument list, no shell; caller-selected local repo.
        [executable, "-C", str(repository), *args],
        text=True,
        stderr=subprocess.DEVNULL,
    ).strip()


def source_info(repository: Path) -> dict[str, str]:
    repository = repository.resolve()
    scope = ("ai-service/src", "ai-service/pyproject.toml", "ai-service/uv.lock")
    if git(repository, "diff", "HEAD", "--", *scope) or git(
        repository,
        "ls-files",
        "--others",
        "--exclude-standard",
        "--",
        *scope,
    ):
        raise BenchmarkError("RUNTIME_SOURCE_MUST_BE_CLEAN")
    return {
        "commit": git(repository, "rev-parse", "HEAD"),
        "sourceTree": git(repository, "rev-parse", "HEAD:ai-service/src"),
        "lockHash": hashlib.sha256((repository / "ai-service/uv.lock").read_bytes()).hexdigest(),
        "pyprojectHash": hashlib.sha256(
            (repository / "ai-service/pyproject.toml").read_bytes()
        ).hexdigest(),
    }


class Worker:
    def __init__(self, process: asyncio.subprocess.Process) -> None:
        self.process = process

    @classmethod
    async def start(cls, python: str, config: dict[str, Any]) -> tuple[Worker, dict[str, Any]]:
        env = os.environ.copy()
        # Never import edupilot_ai from this harness checkout or an inherited PYTHONPATH.
        env["PYTHONPATH"] = str(HARNESS_ROOT)
        process = await asyncio.create_subprocess_exec(
            python,
            "-m",
            "tests.benchmarks.worker",
            cwd=HARNESS_ROOT,
            env=env,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
            limit=8 * 1024 * 1024,
        )
        worker = cls(process)
        try:
            ready = await worker.exchange(config, limit_seconds=30)
            if ready.get("ready") is not True:
                raise BenchmarkError("WORKER_PREFLIGHT_FAILED")
            return worker, ready
        except BaseException:
            await worker.close()
            raise

    async def exchange(self, value: dict[str, Any], *, limit_seconds: float) -> dict[str, Any]:
        async def transfer() -> dict[str, Any]:
            if self.process.stdin is None or self.process.stdout is None:
                raise BenchmarkError("WORKER_PIPE_UNAVAILABLE")
            self.process.stdin.write((json.dumps(value) + "\n").encode())
            await self.process.stdin.drain()
            line = await self.process.stdout.readline()
            if not line:
                raise BenchmarkError("WORKER_EXITED_WITHOUT_RESULT")
            result: dict[str, Any] = json.loads(line)
            if "fatal" in result:
                raise BenchmarkError("WORKER_FAILED_" + str(result["fatal"]))
            return result

        async with asyncio.timeout(limit_seconds):
            return await transfer()

    async def close(self) -> None:
        if self.process.returncode is not None:
            return
        if self.process.stdin is not None:
            with contextlib.suppress(BrokenPipeError, ConnectionResetError):
                self.process.stdin.write(b'{"command":"close"}\n')
                await self.process.stdin.drain()
                self.process.stdin.close()
        try:
            await asyncio.wait_for(self.process.wait(), 3)
        except TimeoutError:
            with contextlib.suppress(ProcessLookupError):
                self.process.terminate()
            try:
                await asyncio.wait_for(self.process.wait(), 3)
            except TimeoutError:
                with contextlib.suppress(ProcessLookupError):
                    self.process.kill()
                await self.process.wait()


def prepare(args: argparse.Namespace) -> None:
    bundle = read_json(args.input)
    suite = build_suite(bundle["snapshot"], bundle["question"], bundle["followupQuestion"])
    validate_suite(suite)
    output = make_output(args.output, [REPOSITORY])
    write_json(output / "suite.json", suite)
    print(json.dumps({"preparedCases": len(suite["cases"]), "liveCalls": 0}))


async def compare(args: argparse.Namespace) -> None:
    live = args.command == "run"
    if live and (not args.live or args.max_provider_calls is None or args.env_file is None):
        raise BenchmarkError("LIVE_FLAG_ENV_FILE_AND_CALL_LIMIT_REQUIRED")
    if live and not args.env_file.is_file():
        raise BenchmarkError("ENV_FILE_NOT_FOUND")
    suite = read_json(args.suite)
    validate_suite(suite)
    cases = {case["id"]: case for case in suite["cases"]}
    selected = args.cases.split(",") if args.cases else list(cases)
    if len(set(selected)) != len(selected) or not set(selected) <= set(cases):
        raise BenchmarkError("UNKNOWN_OR_DUPLICATE_CASE_SELECTION")
    slots = schedule(selected, args.repetitions)
    repositories = {"before": args.before.resolve(), "after": args.after.resolve()}
    sources = {name: source_info(repo) for name, repo in repositories.items()}
    for field in ("lockHash", "pyprojectHash"):
        if sources["before"][field] != sources["after"][field]:
            raise BenchmarkError("DEPENDENCIES_DIFFER_BETWEEN_REVISIONS")
    controls = read_json(args.settings) if args.settings else {}
    if not isinstance(controls, dict) or not set(controls) <= CONFIG_FIELDS:
        raise BenchmarkError("UNSUPPORTED_SETTINGS_OVERRIDE")
    output = make_output(args.output, [REPOSITORY, *repositories.values()])
    private = output / "private"
    private.mkdir(mode=0o700)
    write_json(private / "suite.json", suite)
    budget = (
        RequestBudget.create(private / "budget.sqlite", args.max_provider_calls) if live else None
    )
    workers: dict[str, Worker] = {}
    ready: dict[str, dict[str, Any]] = {}
    rows: list[dict[str, Any]] = []
    status = "PREFLIGHT_FAILED"
    started_at = datetime.now(UTC).isoformat()
    try:
        for name, repository in repositories.items():
            worker, info = await Worker.start(
                args.python,
                {
                    "repository": str(repository),
                    "live": live,
                    "envFile": str(args.env_file.resolve()) if live else None,
                    "budgetPath": str(private / "budget.sqlite"),
                    "settings": controls,
                    "cases": [cases[case_id] for case_id in selected],
                },
            )
            workers[name], ready[name] = worker, info
        if ready["before"] != ready["after"]:
            raise BenchmarkError("EFFECTIVE_SETTINGS_OR_RUNTIME_DIFFER")
        planned = sum(expected_calls(slot["case"]) for slot in slots)
        print(
            json.dumps(
                {
                    "preflight": "PASS",
                    "turns": len(slots),
                    "nominalProviderCalls": planned,
                    "live": live,
                }
            ),
            flush=True,
        )
        if not live:
            status = "PREFLIGHT_PASS"
        else:
            status = "INCOMPLETE"
        for index, slot in enumerate(slots if live else [], 1):
            if budget is None:
                raise BenchmarkError("BUDGET_NOT_INITIALIZED")
            for name, repository in repositories.items():
                if source_info(repository) != sources[name]:
                    raise BenchmarkError("RUNTIME_CHANGED_DURING_RUN")
            cap, prior = budget.counts()
            if cap - prior < expected_calls(slot["case"]):
                status = "BUDGET_STOP"
                break
            case = cases[slot["case"]]
            slot_id = f"{index:03d}-{slot['case']}-{slot['variant']}-r{slot['repetition']}"
            row: dict[str, Any] = {
                **slot,
                "slot": slot_id,
                "inputHash": case["inputHash"],
                "success": False,
            }
            rows.append(row)
            try:
                result = await workers[slot["variant"]].exchange(
                    {"command": "turn", "case": case},
                    limit_seconds=float(ready["before"]["settings"]["turn_timeout_seconds"]) + 30,
                )
                # Preserve the private evidence even if validation/aggregation below fails.
                write_json(private / f"{slot_id}.json", result)
                row.update(result["summary"])
                if row["inputHash"] != case["inputHash"]:
                    raise BenchmarkError("WORKER_INPUT_MISMATCH")
                _, used = budget.counts()
                row.update(summarize_metrics(result["metrics"], used - prior))
            except Exception as exc:
                row.update(success=False, exceptionType=type(exc).__name__)
                if isinstance(exc, BenchmarkError):
                    row["harnessErrorCode"] = str(exc)
            finally:
                _, used = budget.counts()
                row["providerAttempts"] = used - prior
                print(
                    json.dumps(
                        {"slot": slot_id, "success": row["success"], "used": used, "limit": cap}
                    ),
                    flush=True,
                )
            if not row["success"]:
                status = "FAILED_STOP"
                break
        else:
            if live:
                status = "COMPLETED_NOT_QUALITY_REVIEWED"
    finally:
        cleanup = await asyncio.gather(
            *(worker.close() for worker in workers.values()), return_exceptions=True
        )
        cleanup_errors = [
            type(result).__name__ for result in cleanup if isinstance(result, BaseException)
        ]
        if cleanup_errors:
            status = "CLEANUP_FAILED"
        cap, used = budget.counts() if budget else (0, 0)
        unattributed = used - sum(row.get("providerAttempts", 0) for row in rows)
        manifest = {
            "status": status,
            "scope": "LOCAL_ASGI_REAL_XAI_NOT_SPRING_FE" if live else "OFFLINE_PREFLIGHT",
            "sources": sources,
            "effective": ready,
            "suiteHash": digest(suite),
            "settingsOverrides": controls,
            "schedule": slots,
            "startedAt": started_at,
            "finishedAt": datetime.now(UTC).isoformat(),
            "providerCallLimit": cap,
            "providerCallsUsed": used,
            "unattributedProviderAttempts": unattributed,
            "cleanupErrors": cleanup_errors,
            "note": (
                "Sequential persistent worker per revision; provider cache not cleared; "
                "no automatic quality PASS."
            ),
        }
        write_json(output / "manifest.json", manifest)
        write_json(output / "metrics.json", rows)
        write_json(private / "quality-review.json", review_template(rows, suite["qualityRubric"]))
        with (output / "report.md").open("x", encoding="utf-8") as handle:
            handle.write(
                report_markdown(
                    rows, used=used, cap=cap, planned=len(slots), unattributed=unattributed
                )
            )
    if status not in {"PREFLIGHT_PASS", "COMPLETED_NOT_QUALITY_REVIEWED"}:
        raise BenchmarkError(status)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    prep = commands.add_parser(
        "prepare", help="Build seven private cases from a test snapshot (offline)"
    )
    prep.add_argument("--input", type=Path, required=True)
    prep.add_argument("--output", type=Path, required=True)
    for command in ("check", "run"):
        entry = commands.add_parser(command)
        entry.add_argument("--suite", type=Path, required=True)
        entry.add_argument("--before", type=Path, required=True)
        entry.add_argument("--after", type=Path, required=True)
        entry.add_argument("--python", default=sys.executable)
        entry.add_argument("--settings", type=Path)
        entry.add_argument("--cases", help="Comma-separated case IDs; default all seven")
        entry.add_argument("--repetitions", type=int, default=3)
        entry.add_argument("--output", type=Path, required=True)
        if command == "run":
            entry.add_argument("--live", action="store_true")
            entry.add_argument("--env-file", type=Path)
            entry.add_argument("--max-provider-calls", type=int)
    render = commands.add_parser("report", help="Rebuild metadata-only Markdown without network")
    render.add_argument("--result", type=Path, required=True)
    render.add_argument("--output", type=Path, required=True)
    return root


def main() -> None:
    os.umask(0o077)
    args = parser().parse_args()
    try:
        if args.command == "prepare":
            prepare(args)
        elif args.command == "report":
            manifest = read_json(args.result / "manifest.json")
            rows = read_json(args.result / "metrics.json")
            with args.output.open("x", encoding="utf-8") as handle:
                handle.write(
                    report_markdown(
                        rows,
                        used=manifest["providerCallsUsed"],
                        cap=manifest["providerCallLimit"],
                        planned=len(manifest["schedule"]),
                        unattributed=manifest.get("unattributedProviderAttempts", 0),
                    )
                )
        else:
            asyncio.run(compare(args))
    except (Exception, KeyboardInterrupt) as exc:
        print(
            json.dumps(
                {"error": str(exc) if isinstance(exc, BenchmarkError) else type(exc).__name__}
            )
        )
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
