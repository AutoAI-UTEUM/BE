"""Build fixed private requests from a user-supplied, non-production test snapshot."""

from copy import deepcopy
from typing import Any

from tests.benchmarks.common import BenchmarkError, digest

CASE_IDS = ("explain", "qa_new", "qa_followup", "quiz_mcq", "quiz_ox", "quiz_short", "quiz_essay")


def build_suite(snapshot: dict[str, Any], question: str, followup: str) -> dict[str, Any]:
    """Do not commit the resulting document: it contains supplied source/conversation text."""
    if not question.strip() or not followup.strip():
        raise BenchmarkError("QUESTIONS_REQUIRED")
    thread = snapshot["context"].get("qaThreadDigest")
    if not isinstance(thread, dict) or not thread.get("threadRef"):
        raise BenchmarkError("FOLLOWUP_SNAPSHOT_WITH_THREAD_REF_REQUIRED")
    cases = []
    for case_id in CASE_IDS:
        payload = deepcopy(snapshot)
        payload["turnId"] = "benchmark-template"
        context = payload["context"]
        context.pop("quizContext", None)
        if case_id == "explain":
            payload["session"]["pageStatus"] = "NOT_EXPLAINED"
            event = {"eventType": "EXPLAIN_CURRENT_PAGE", "payload": {"detailLevel": "NORMAL"}}
        elif case_id.startswith("qa_"):
            payload["session"]["pageStatus"] = "EXPLAINED"
            event = {
                "eventType": "USER_QUESTION",
                "payload": {"message": question if case_id == "qa_new" else followup},
            }
            if case_id == "qa_new":
                context["qaThreadDigest"] = None
                context["recentMessages"] = []
                context["conversationSummary"] = None
        else:
            payload["session"]["pageStatus"] = "EXPLAINED"
            event = {
                "eventType": "QUIZ_TYPE_SELECTED",
                "payload": {"quizType": case_id[5:].upper()},
            }
            if snapshot["context"].get("quizContext") is not None:
                context["quizContext"] = deepcopy(snapshot["context"]["quizContext"])
        payload["event"] = event
        cases.append({"id": case_id, "payload": payload, "inputHash": digest(payload)})
    return {"version": 1, "cases": cases, "qualityRubric": quality_rubric()}


def quality_rubric() -> dict[str, list[str]]:
    return {
        "all": [
            "자료/대화에 근거하며 확인되지 않은 사실을 만들지 않는가?",
            "조건·예외·성질과 알고리즘의 성공/수렴/유일성 보장을 구분하는가?",
            "속도를 위해 필요한 설명·정답·해설·평가 기준을 생략하지 않았는가?",
        ],
        "explain": ["현재 페이지를 설명하고, 퀴즈 제안 여부가 전체 자료 흐름상 적절한가?"],
        "qa_new": ["질문에 직접 답하며 관련 없는 이전 QA를 이어받지 않는가?"],
        "qa_followup": ["직전 주제/지시 대상을 실제로 이어받고 단순 일반론으로 돌아가지 않는가?"],
        "quiz": [
            "출제 범위·난이도가 적절하고 자료 밖 내용을 요구하지 않는가?",
            "문항/정답/해설이 서로 맞고, SHORT/ESSAY 기준으로 실제 채점할 수 있는가?",
            "선택지나 OX 정답 위치에 반복 편향이 있는가? (분포만으로 오류 확정 금지)",
        ],
    }


def validate_suite(suite: dict[str, Any]) -> None:
    cases = suite.get("cases")
    if suite.get("version") != 1 or not isinstance(cases, list) or not cases:
        raise BenchmarkError("INVALID_SUITE")
    rubric = suite.get("qualityRubric")
    if not isinstance(rubric, dict) or any(
        not isinstance(rubric.get(key), list)
        or not rubric[key]
        or any(not isinstance(item, str) or not item.strip() for item in rubric[key])
        for key in quality_rubric()
    ):
        raise BenchmarkError("INVALID_QUALITY_RUBRIC")
    seen = set()
    for case in cases:
        case_id = case["id"]
        if case_id not in CASE_IDS or case_id in seen:
            raise BenchmarkError("INVALID_OR_DUPLICATE_CASE")
        seen.add(case_id)
        payload = case["payload"]
        event = payload["event"]
        expected_event = (
            "QUIZ_TYPE_SELECTED"
            if case_id.startswith("quiz_")
            else "EXPLAIN_CURRENT_PAGE"
            if case_id == "explain"
            else "USER_QUESTION"
        )
        if event["eventType"] != expected_event or (
            case_id.startswith("quiz_") and event["payload"].get("quizType") != case_id[5:].upper()
        ):
            raise BenchmarkError("CASE_EVENT_MISMATCH")
        if case["inputHash"] != digest(payload):
            raise BenchmarkError("FIXTURE_HASH_MISMATCH")
        context = payload["context"]
        if not (context.get("xaiFileId") or "").strip():
            raise BenchmarkError("ATTACHED_PDF_REQUIRED")
        if not (context.get("currentPageText") or "").strip():
            raise BenchmarkError("NONEMPTY_PAGE_REQUIRED")


def schedule(case_ids: list[str], repetitions: int) -> list[dict[str, Any]]:
    if repetitions < 1:
        raise BenchmarkError("POSITIVE_REPETITIONS_REQUIRED")
    rows = []
    for rep in range(1, repetitions + 1):
        for index, case_id in enumerate(case_ids):
            order = ("before", "after") if (rep + index) % 2 else ("after", "before")
            for variant in order:
                rows.append({"case": case_id, "variant": variant, "repetition": rep})
    return rows


def expected_calls(case_id: str) -> int:
    return 1 if case_id.startswith("quiz_") else 2
