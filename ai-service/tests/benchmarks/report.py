"""Metadata-only summaries; HTTP success is deliberately not a quality verdict."""

import math
import re
from collections import Counter
from statistics import median
from typing import Any

from tests.benchmarks.common import BenchmarkError

EVENT_TYPES = {"status", "thought_summary", "content_delta", "heartbeat", "completed", "error"}


def inspect_events(events: list[dict[str, Any]], case: dict[str, Any]) -> dict[str, Any]:
    counts = Counter(event.get("type", "envelope") for event in events)
    completed = [event.get("result") for event in events if event.get("type") == "completed"]
    valid = (
        bool(events)
        and events[0] == {"type": "status", "stage": "PLANNING"}
        and events[-1].get("type") == "completed"
        and counts["completed"] == 1
        and counts["error"] == 0
        and set(counts) <= EVENT_TYPES
        and len(completed) == 1
    )
    codes = []
    for event in events:
        error = event.get("error")
        codes.append(event.get("code") or (error.get("code") if isinstance(error, dict) else None))
    row: dict[str, Any] = {
        "contractPass": False,
        "eventCounts": dict(counts),
        "errorCodes": sorted(
            {
                code
                for code in codes
                if isinstance(code, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", code)
            }
        ),
    }
    if not valid:
        return row
    result = completed[0]
    if not isinstance(result, dict):
        return row
    text = "".join(message["content"] for message in result["messages"])
    deltas = "".join(event["text"] for event in events if event["type"] == "content_delta")
    row.update(answerChars=len(text), streamInvariant=text == deltas)
    valid = valid and text == deltas
    case_id = case["id"]
    row["quizProposed"] = any(
        action.get("yesEvent") == "SHOW_QUIZ_TYPE_SELECT" for action in result["uiActions"]
    )
    if case_id.startswith("quiz_"):
        quiz = result.get("quiz") or {}
        questions = quiz.get("questions", [])
        expected = case["payload"]["context"].get("quizContext")
        page = case["payload"]["session"]["currentPage"]
        coverage = expected["coverage"] if expected else {"startPage": page, "endPage": page}
        valid = (
            valid
            and quiz.get("quizType") == case_id[5:].upper()
            and quiz.get("coverage") == coverage
            and quiz.get("questionCount") == len(questions)
            and 5 <= len(questions) <= 10
            and counts["content_delta"] == 0
        )
        row["questionCount"] = len(questions)
        positions: Counter[str] = Counter()
        for question in questions:
            for index, choice in enumerate(question.get("choices", []), 1):
                if choice["choiceId"] == question.get("answerChoiceId"):
                    positions[str(index)] += 1
        row["mcqAnswerPositions"] = dict(positions)
    else:
        expected_type = "EXPLANATION" if case_id == "explain" else "QA"
        valid = (
            valid
            and bool(text.strip())
            and any(message["messageType"] == expected_type for message in result["messages"])
        )
        patch = result["statePatch"]
        if case_id == "explain":
            valid = valid and patch.get("pageStatus") == "EXPLAINED"
        else:
            mode = "FOLLOW_UP" if case_id == "qa_followup" else "START_NEW"
            qa_thread = patch.get("qaThread", {})
            row["qaThreadMode"] = qa_thread.get("mode")
            valid = valid and qa_thread.get("mode") == mode
            if mode == "FOLLOW_UP":
                thread = case["payload"]["context"]["qaThreadDigest"]["threadRef"]
                valid = valid and qa_thread.get("threadRef") == thread
    row["contractPass"] = bool(valid)
    return row


def number(value: Any) -> bool:
    return type(value) in {int, float} and math.isfinite(value) and value >= 0


def summarize_metrics(records: list[dict[str, Any]], attempts: int) -> dict[str, Any]:
    provider: dict[tuple[str, int], dict[str, Any]] = {}
    for record in records:
        if record.get("message") != "xAI chat completion finished":
            continue
        call_id, attempt = record.get("llmCallId"), record.get("attempt")
        if not isinstance(call_id, str) or type(attempt) is not int:
            continue
        key = call_id, attempt
        if key in provider and provider[key] != record:
            raise BenchmarkError("CONFLICTING_PROVIDER_METRICS")
        provider[key] = record
    calls = list(provider.values())
    complete = len(calls) == attempts and attempts > 0
    row: dict[str, Any] = {
        "providerAttempts": attempts,
        "providerMetricsComplete": complete,
        "providerModels": sorted({r["providerModel"] for r in calls if r.get("providerModel")}),
        "inputTokens": None,
        "outputTokens": None,
        "reasoningTokens": None,
        "cachedInputTokens": None,
        "costUsdTicks": None,
        "numServerSideToolsUsed": None,
        "providerErrorCodes": sorted({r["errorCode"] for r in calls if r.get("errorCode")}),
    }
    for field in (
        "inputTokens",
        "outputTokens",
        "reasoningTokens",
        "cachedInputTokens",
        "costUsdTicks",
        "numServerSideToolsUsed",
    ):
        if complete and all(
            type(r.get(field)) is int and r[field] >= 0 and r.get("providerUsageFinal") is True
            for r in calls
        ):
            row[field] = sum(r[field] for r in calls)
    inputs, cached = row["inputTokens"], row["cachedInputTokens"]
    row["cacheRatio"] = (
        cached / inputs if inputs and cached is not None and cached <= inputs else None
    )
    finals = [
        record
        for record in records
        if record.get("message") in {"turn stream completed", "turn stream failed"}
    ]
    for target, field in (
        ("firstContentMs", "firstContentMs"),
        ("resultReadyMs", "resultReadyMs"),
        ("durationMs", "durationMs"),
    ):
        value = finals[-1].get(field) if len(finals) == 1 else None
        row[target] = value if number(value) else None
    plans = [r for r in records if r.get("message") == "turn planning finished"]
    row["plannerMs"] = plans[0].get("durationMs") if len(plans) == 1 else None
    row["plannerAttempts"] = plans[0].get("plannerAttempts") if len(plans) == 1 else None
    row["schemaRetryCount"] = sum(
        r.get("message") == "planner attempt failed" and r.get("status") == "RETRYING"
        for r in records
    )
    # A logical call's duration is cumulative across retries; never sum these durations.
    return row


def review_template(
    rows: list[dict[str, Any]], rubric: dict[str, list[str]]
) -> list[dict[str, Any]]:
    return [
        {
            "slot": row["slot"],
            "checks": [
                {"criterion": criterion, "status": "NOT_REVIEWED", "evidence": ""}
                for criterion in rubric["all"]
                + rubric["quiz" if row["case"].startswith("quiz_") else row["case"]]
            ],
        }
        for row in rows
    ]


def report_markdown(
    rows: list[dict[str, Any]], *, used: int, cap: int, planned: int, unattributed: int = 0
) -> str:
    lines = [
        "# AI 로컬 전후 비교 (Spring/FE 미포함)",
        "",
        f"실행 {len(rows)}/{planned}턴, provider 전송 시도 {used}/{cap}회.",
        f"턴 결과에 귀속하지 못한 전송 {unattributed}회. "
        "하나라도 있으면 전체 비용 합계는 미상입니다.",
        "HTTP/계약 성공은 교육 품질 PASS가 아닙니다. "
        "private/quality-review.json을 별도 검토하세요.",
        "미수집 값은 —이며 0으로 채우지 않습니다. 표는 성공한 턴의 중앙값 [최소, 최대]입니다.",
        "첫 본문은 서버 로그 기준이며 heartbeat/ASGI 클라이언트 버퍼링 시간은 제외합니다.",
        "공급자 캐시는 초기화할 수 없습니다. 소표본으로 개선율·유의성·p95를 확정하지 않습니다.",
        "",
        "| 시나리오/버전 | 성공/시도 | Planner s | 첫 본문 s | 준비 완료 s | 전체 s |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for case in dict.fromkeys(r["case"] for r in rows):
        for variant in ("before", "after"):
            group = [r for r in rows if r["case"] == case and r["variant"] == variant]
            good = [r for r in group if r.get("success") is True]
            cells = []
            for field in ("plannerMs", "firstContentMs", "resultReadyMs", "durationMs"):
                values = [r[field] / 1000 for r in good if number(r.get(field))]
                cells.append(
                    f"{median(values):.2f} [{min(values):.2f}, {max(values):.2f}]"
                    if values
                    else "—"
                )
            lines.append(
                f"| {case}/{variant} | {len(good)}/{len(group)} | " + " | ".join(cells) + " |"
            )
    lines += ["", "## 토큰·비용·오류", ""]
    for variant in ("before", "after"):
        group = [r for r in rows if r["variant"] == variant]
        totals: dict[str, int | None] = {}
        for field in ("inputTokens", "outputTokens", "cachedInputTokens", "costUsdTicks"):
            totals[field] = (
                sum(r[field] for r in group)
                if not unattributed and group and all(type(r.get(field)) is int for r in group)
                else None
            )
        failures = sum(not r.get("success", False) for r in group)
        errors = Counter(
            code
            for r in group
            for code in set(r.get("errorCodes", []) + r.get("providerErrorCodes", []))
            | ({r["harnessErrorCode"]} if r.get("harnessErrorCode") else set())
        )
        lines.append(
            f"- {variant}: 합계 {totals}; 실패 {failures}건; 오류 {dict(errors)} "
            "(실패 시도 비용 포함, 결측이면 None)"
        )
    lines += [
        "",
        "조건/정답 정확도·문항 수·본문 길이·MCQ 정답 위치는 "
        "metrics.json 및 비공개 응답과 대조합니다.",
        "실패/중도 중단·불완전한 전후 쌍은 "
        "성공 표본만의 시간으로 숨기지 말고 함께 보고해야 합니다.",
    ]
    return "\n".join(lines) + "\n"
