"""Offline-only tests for the explicit-opt-in local evaluation harness."""

import json
import logging
import stat
import sys
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from pathlib import Path
from typing import Any

import httpx
import pytest

from edupilot_ai.models.plan import ToolName
from edupilot_ai.models.quiz import QuizType
from tests.benchmarks.__main__ import HARNESS_ROOT, Worker, compare, parser, source_info
from tests.benchmarks.common import (
    BenchmarkError,
    BudgetTransport,
    OfflineTransport,
    RequestBudget,
    digest,
    make_output,
    read_json,
    write_json,
)
from tests.benchmarks.report import (
    inspect_events,
    report_markdown,
    review_template,
    summarize_metrics,
)
from tests.benchmarks.scenarios import (
    CASE_IDS,
    build_suite,
    expected_calls,
    schedule,
    validate_suite,
)
from tests.benchmarks.worker import MetricsHandler, execute_turn
from tests.fakes import FakeLlm
from tests.planner_fixtures import planner_output
from tests.test_quiz_grading import make_quiz
from tests.test_turn_contract import make_explain_plan, make_plan


@pytest.fixture
def suite(turn_payload: dict[str, Any]) -> dict[str, Any]:
    snapshot = deepcopy(turn_payload)
    snapshot["context"]["xaiFileId"] = "file-private-do-not-log"
    snapshot["context"]["qaThreadDigest"] = {
        "threadRef": "thread-eval",
        "digest": "학생은 평균과 편차의 구분을 질문했다.",
    }
    snapshot["context"]["recentMessages"] = [
        {"senderType": "USER", "content": "편차는 평균과 어떻게 다른가요?"},
        {"senderType": "ASSISTANT", "content": "각 값에서 평균을 뺀 차이가 편차입니다."},
    ]
    return build_suite(snapshot, "편차가 무엇인가요?", "방금 말한 차이를 예시로 보여줘")


def test_fixed_suite_and_counterbalanced_schedule(suite: dict[str, Any]) -> None:
    original = deepcopy(suite)
    validate_suite(suite)
    slots = schedule(list(CASE_IDS), 3)
    assert len(slots) == 42
    assert sum(expected_calls(slot["case"]) for slot in slots) == 60
    assert [s["variant"] for s in slots[:4]] == ["before", "after", "after", "before"]
    assert [c["id"] for c in suite["cases"]] == list(CASE_IDS)
    assert suite == original
    assert suite["cases"][1]["payload"]["context"]["recentMessages"] == []
    assert suite["cases"][2]["payload"]["context"]["qaThreadDigest"]["threadRef"] == "thread-eval"


@pytest.mark.parametrize("defect", ["hash", "duplicate", "file", "empty", "event", "rubric"])
def test_bad_suite_fails_before_provider(suite: dict[str, Any], defect: str) -> None:
    case = suite["cases"][0]
    if defect == "hash":
        case["payload"]["context"]["currentPageText"] = "changed"
    elif defect == "duplicate":
        suite["cases"].append(deepcopy(case))
    elif defect == "rubric":
        suite["qualityRubric"] = {}
    else:
        if defect == "file":
            case["payload"]["context"]["xaiFileId"] = None
        elif defect == "empty":
            case["payload"]["context"]["currentPageText"] = " "
        else:
            case["payload"]["event"]["eventType"] = "USER_QUESTION"
        case["inputHash"] = digest(case["payload"])
    with pytest.raises(BenchmarkError):
        validate_suite(suite)


def test_private_files_no_overwrite_or_repo_output(tmp_path: Path) -> None:
    repository = tmp_path / "repo"
    repository.mkdir()
    with pytest.raises(BenchmarkError):
        make_output(repository / "out", [repository])
    output = make_output(tmp_path / "outside", [repository])
    write_json(output / "private.json", {"input": "private"})
    assert stat.S_IMODE(output.stat().st_mode) == 0o700
    assert stat.S_IMODE((output / "private.json").stat().st_mode) == 0o600
    with pytest.raises(FileExistsError):
        write_json(output / "private.json", {})
    link = output / "link.json"
    link.symlink_to(output / "private.json")
    with pytest.raises(FileExistsError):
        write_json(link, {})


def test_shared_budget_is_atomic_and_persistent(tmp_path: Path) -> None:
    path = tmp_path / "budget.sqlite"
    budget = RequestBudget.create(path, 5)

    def reserve(_: int) -> bool:
        try:
            RequestBudget(path).reserve()
        except BenchmarkError:
            return False
        return True

    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(reserve, range(30))) == 5
    assert budget.counts() == (5, 5)
    with pytest.raises(FileExistsError):
        RequestBudget.create(path, 100)


async def test_transport_counts_failures_and_retries_before_send(tmp_path: Path) -> None:
    budget = RequestBudget.create(tmp_path / "budget.sqlite", 2)
    calls = 0

    async def send(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise httpx.ConnectError("PRIVATE ERROR", request=request)

    async with httpx.AsyncClient(
        transport=BudgetTransport(httpx.MockTransport(send), budget)
    ) as client:
        for _ in range(2):
            with pytest.raises(httpx.ConnectError):
                await client.post("https://api.x.ai/v1/responses")
        with pytest.raises(BenchmarkError, match="BUDGET_EXHAUSTED"):
            await client.post("https://api.x.ai/v1/chat/completions")
        with pytest.raises(BenchmarkError, match="UNEXPECTED_PROVIDER_ROUTE"):
            await client.post("https://api.x.ai/v1/files")
    assert calls == 2
    assert budget.counts() == (2, 2)


async def test_offline_mode_has_no_network() -> None:
    async with httpx.AsyncClient(transport=OfflineTransport()) as client:
        with pytest.raises(BenchmarkError, match="LIVE_MODE_NOT_ENABLED"):
            await client.post("https://api.x.ai/v1/responses")


def provider_record(call: str, *, cost: int | None = 50, attempt: int = 1) -> dict[str, Any]:
    return {
        "message": "xAI chat completion finished",
        "llmCallId": call,
        "attempt": attempt,
        "inputTokens": 100,
        "outputTokens": 20,
        "cachedInputTokens": 50,
        "costUsdTicks": cost,
        "providerUsageFinal": True,
        "durationMs": 5000 * attempt,
    }


def test_metrics_dedupe_final_usage_and_do_not_add_nested_times() -> None:
    first = provider_record("a")
    records = [
        first,
        deepcopy(first),
        provider_record("a", attempt=2),
        provider_record("b"),
        {"message": "turn planning finished", "durationMs": 9000, "plannerAttempts": 2},
        {
            "message": "turn stream completed",
            "durationMs": 14000,
            "firstContentMs": 12000,
            "resultReadyMs": 13900,
        },
    ]
    row = summarize_metrics(records, 3)
    assert row["costUsdTicks"] == 150
    assert row["inputTokens"] == 300
    assert row["cacheRatio"] == 0.5
    assert row["durationMs"] == 14000
    assert row["firstContentMs"] == 12000
    assert row["plannerMs"] == 9000
    assert row["reasoningTokens"] is None


@pytest.mark.parametrize("defect", ["missing_call", "missing_usage", "partial_usage", "bool"])
def test_usage_incomplete_is_not_free_or_a_partial_total(defect: str) -> None:
    records = [provider_record("a"), provider_record("b")]
    if defect == "missing_call":
        records.pop()
    elif defect == "missing_usage":
        records[1]["costUsdTicks"] = None
    elif defect == "partial_usage":
        records[1]["providerUsageFinal"] = False
    else:
        records[1]["costUsdTicks"] = True
    assert summarize_metrics(records, 2)["costUsdTicks"] is None


def test_zero_usage_is_preserved_and_conflicting_duplicates_rejected() -> None:
    record = provider_record("a", cost=0)
    assert summarize_metrics([record], 1)["costUsdTicks"] == 0
    with pytest.raises(BenchmarkError, match="CONFLICTING"):
        summarize_metrics([record, provider_record("a", cost=2)], 1)


@pytest.mark.parametrize("case_id", CASE_IDS)
async def test_fake_llm_real_ndjson_case_and_private_review(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    suite: dict[str, Any],
    case_id: str,
) -> None:
    case = next(c for c in suite["cases"] if c["id"] == case_id)
    if case_id.startswith("quiz_"):
        fake_llm.queue(make_quiz(QuizType(case_id[5:].upper())))
    else:
        if case_id == "explain":
            plan = make_explain_plan(propose_quiz=True)
        else:
            plan = make_plan(
                ToolName.ANSWER_QUESTION,
                {
                    "qaThreadMode": "FOLLOW_UP" if case_id == "qa_followup" else "START_NEW",
                    "threadRef": "thread-eval" if case_id == "qa_followup" else None,
                },
                "answer the question",
            )
        fake_llm.queue(planner_output(plan))
        fake_llm.queue_text_stream("PRIVATE ANSWER ", "CONTINUED")
    sink = MetricsHandler()
    logger = logging.getLogger("edupilot_ai")
    logger.addHandler(sink)
    try:
        result = await execute_turn(client, case, "contract-test-token", sink)
    finally:
        logger.removeHandler(sink)
    row = result["summary"]
    assert row["success"] is True
    assert row["inputHash"] == digest(case["payload"])
    assert len(fake_llm.calls) + len(fake_llm.stream_calls) == expected_calls(case_id)
    assert "PRIVATE ANSWER" not in json.dumps(result["metrics"])
    assert "file-private" not in json.dumps(result["metrics"])
    if case_id.startswith("quiz_"):
        assert "firstContentMs" not in next(
            r for r in result["metrics"] if r["message"] == "turn stream completed"
        )
    review = review_template([{"slot": "1", "case": case_id}], suite["qualityRubric"])
    assert all(c["status"] == "NOT_REVIEWED" for c in review[0]["checks"])
    # A final error or duplicated completed is never a successful stream.
    assert not inspect_events(result["events"] + [{"type": "error"}], case)["contractPass"]


def test_report_separates_failures_missing_measurements_and_manual_quality() -> None:
    rows = [
        {
            "case": "explain",
            "variant": "before",
            "success": True,
            "durationMs": 1000,
            "costUsdTicks": 5,
        },
        {"case": "explain", "variant": "before", "success": False, "durationMs": 100000},
    ]
    report = report_markdown(rows, used=3, cap=4, planned=6)
    assert "1/2" in report and "1.00 [1.00, 1.00]" in report
    assert "'costUsdTicks': None" in report
    assert "품질 PASS가 아닙니다" in report
    assert "—" in report


async def test_worker_preflight_uses_requested_revision_without_live_calls(
    suite: dict[str, Any],
) -> None:
    repository = HARNESS_ROOT.parent
    worker, ready = await Worker.start(
        sys.executable,
        {
            "repository": str(repository),
            "live": False,
            "cases": suite["cases"],
            "settings": {},
        },
    )
    try:
        assert ready["ready"] is True
        assert ready["profiles"]["quiz"]["model"]
        with pytest.raises(BenchmarkError, match="LIVE_MODE_NOT_ENABLED"):
            await worker.exchange({"command": "turn", "case": suite["cases"][0]}, limit_seconds=5)
    finally:
        await worker.close()
    assert worker.process.returncode is not None


async def test_preflight_cli_persists_zero_calls_and_live_requires_opt_in(
    tmp_path: Path,
    suite: dict[str, Any],
) -> None:
    fixture = tmp_path / "suite.json"
    write_json(fixture, suite)
    output = tmp_path / "check"
    common = [
        "--suite",
        str(fixture),
        "--before",
        str(HARNESS_ROOT.parent),
        "--after",
        str(HARNESS_ROOT.parent),
        "--output",
        str(output),
    ]
    await compare(parser().parse_args(["check", *common]))
    manifest = read_json(output / "manifest.json")
    assert manifest["status"] == "PREFLIGHT_PASS"
    assert manifest["providerCallsUsed"] == 0
    assert manifest["sources"]["before"] == source_info(HARNESS_ROOT.parent)
    assert "file-private" not in json.dumps(manifest)
    with pytest.raises(BenchmarkError, match="LIVE_FLAG_ENV_FILE_AND_CALL_LIMIT_REQUIRED"):
        await compare(parser().parse_args(["run", *common]))


@pytest.mark.parametrize(
    ("cap", "defect", "status", "turns"),
    [
        (4, "none", "COMPLETED_NOT_QUALITY_REVIEWED", 2),
        (3, "none", "BUDGET_STOP", 1),
        (4, "response", "FAILED_STOP", 1),
        (4, "metrics", "FAILED_STOP", 1),
        (4, "crash", "FAILED_STOP", 1),
        (4, "cleanup", "CLEANUP_FAILED", 2),
    ],
)
async def test_supervisor_stops_preserves_artifacts_and_closes_workers(
    tmp_path: Path,
    suite: dict[str, Any],
    monkeypatch: pytest.MonkeyPatch,
    cap: int,
    defect: str,
    status: str,
    turns: int,
) -> None:
    fixture = tmp_path / "suite.json"
    write_json(fixture, suite)
    env = tmp_path / "fake-environment"
    env.write_text("offline supervisor test", encoding="utf-8")
    sent: list[dict[str, Any]] = []
    closed: list[bool] = []

    class FakeWorker:
        def __init__(self, budget: RequestBudget) -> None:
            self.budget = budget

        async def exchange(self, value: dict[str, Any], *, limit_seconds: float) -> dict[str, Any]:
            case = value["case"]
            sent.append(deepcopy(case))
            for _ in range(expected_calls(case["id"])):
                self.budget.reserve()
            if defect == "crash":
                raise BenchmarkError("WORKER_EXITED_WITHOUT_RESULT")
            return {
                "summary": {
                    "success": defect != "response",
                    "inputHash": case["inputHash"],
                    "errorCodes": ["AI_RESPONSE_INVALID"] if defect == "response" else [],
                },
                "events": [],
                "metrics": [
                    provider_record("a"),
                    provider_record("a", cost=2) if defect == "metrics" else provider_record("b"),
                ],
            }

        async def close(self) -> None:
            closed.append(True)
            if defect == "cleanup":
                raise RuntimeError("PRIVATE CLEANUP ERROR")

    async def start(python: str, config: dict[str, Any]) -> tuple[FakeWorker, dict[str, Any]]:
        return FakeWorker(RequestBudget(Path(config["budgetPath"]))), {
            "settings": {"turn_timeout_seconds": 180},
            "ready": True,
        }

    monkeypatch.setattr(Worker, "start", start)
    output = tmp_path / "run"
    args = parser().parse_args(
        [
            "run",
            "--live",
            "--env-file",
            str(env),
            "--max-provider-calls",
            str(cap),
            "--suite",
            str(fixture),
            "--before",
            str(HARNESS_ROOT.parent),
            "--after",
            str(HARNESS_ROOT.parent),
            "--output",
            str(output),
            "--cases",
            "explain",
            "--repetitions",
            "1",
        ]
    )
    if status == "COMPLETED_NOT_QUALITY_REVIEWED":
        await compare(args)
    else:
        with pytest.raises(BenchmarkError, match=status):
            await compare(args)
    manifest = read_json(output / "manifest.json")
    rows = read_json(output / "metrics.json")
    assert manifest["status"] == status
    assert manifest["providerCallsUsed"] == turns * 2 <= cap
    assert len(rows) == len(sent) == turns
    assert closed == [True, True]
    assert all(case == suite["cases"][0] for case in sent)
    assert "NOT_REVIEWED" in (output / "private/quality-review.json").read_text()
    public = (output / "report.md").read_text() + json.dumps(manifest) + json.dumps(rows)
    assert "file-private" not in public
    assert "PRIVATE ANSWER" not in public
    assert "PRIVATE CLEANUP ERROR" not in public
    if defect == "metrics":
        assert (output / "private/001-explain-before-r1.json").is_file()
        assert rows[0]["harnessErrorCode"] == "CONFLICTING_PROVIDER_METRICS"


async def test_offline_preflight_cleanup_failure_is_not_reported_as_success(
    tmp_path: Path, suite: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    class CleanupFailure:
        async def close(self) -> None:
            raise RuntimeError("private")

    async def start(python: str, config: dict[str, Any]) -> tuple[CleanupFailure, dict[str, Any]]:
        return CleanupFailure(), {"ready": True}

    monkeypatch.setattr(Worker, "start", start)
    fixture = tmp_path / "suite.json"
    write_json(fixture, suite)
    output = tmp_path / "check"
    args = parser().parse_args(
        [
            "check",
            "--suite",
            str(fixture),
            "--before",
            str(HARNESS_ROOT.parent),
            "--after",
            str(HARNESS_ROOT.parent),
            "--output",
            str(output),
        ]
    )
    with pytest.raises(BenchmarkError, match="CLEANUP_FAILED"):
        await compare(args)
    assert read_json(output / "manifest.json")["status"] == "CLEANUP_FAILED"


def test_unattributed_transmissions_prevent_complete_cost_totals() -> None:
    report = report_markdown(
        [{"case": "explain", "variant": "before", "success": True, "costUsdTicks": 5}],
        used=2,
        cap=3,
        planned=2,
        unattributed=1,
    )
    assert "귀속하지 못한 전송 1회" in report
    assert "'costUsdTicks': None" in report


def test_metric_sink_drops_payloads_and_exception_text() -> None:
    sink = MetricsHandler()
    record = logging.LogRecord(
        "edupilot_ai", logging.INFO, "", 1, "xAI chat completion finished", (), None
    )
    record.inputTokens = 12
    record.prompt = "PRIVATE PROMPT"
    record.fileId = "file-private"
    record.exceptionType = "ConnectError: PRIVATE ERROR"
    sink.emit(record)
    assert sink.rows == [{"message": "xAI chat completion finished", "inputTokens": 12}]


def test_error_envelope_is_counted_without_its_message(suite: dict[str, Any]) -> None:
    row = inspect_events(
        [{"error": {"code": "TIMEOUT", "message": "PRIVATE ERROR"}}], suite["cases"][0]
    )
    assert row["contractPass"] is False
    assert row["errorCodes"] == ["TIMEOUT"]
    assert "PRIVATE" not in json.dumps(row)
