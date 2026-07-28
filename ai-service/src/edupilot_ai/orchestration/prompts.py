"""Prompt builders separated from orchestration and agent execution."""

import json
from collections.abc import Mapping, Sequence

from edupilot_ai.models.turn import DetailLevel, QaThreadMode
from edupilot_ai.orchestration.context import AgentContext


def plan_messages(
    context: AgentContext,
    *,
    retry: bool,
) -> Sequence[Mapping[str, str]]:
    system = (
        "Return only TurnPlan JSON. Choose a turnGoal then allowed tools. "
        "Never write the learner answer in the Plan. Pipeline tools "
        "GRADE_OPEN_RESPONSE, ASSESS_QUIZ_RESULT, DIAGNOSE_MISCONCEPTION are forbidden. "
        "memoryWrite must be null. FOLLOW_UP requires qaThreadDigest."
    )
    if retry:
        system += " The previous output failed schema validation; regenerate exactly once."
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": context.model_dump_json(by_alias=True)},
    ]


def explainer_messages(
    context: AgentContext,
    detail_level: DetailLevel,
    *,
    structured: bool = True,
) -> Sequence[Mapping[str, str]]:
    payload = {
        "page": context.session.current_page,
        "detailLevel": detail_level.value,
        "currentPageText": context.current_page_text,
        "previousPageText": context.previous_page_text,
        "nextPageText": context.next_page_text,
        "learnerLevel": context.learner_level,
        "learnerConfidence": context.learner_confidence,
        "learnerMemoryDigest": context.learner_memory_digest,
    }
    output_instruction = (
        "Return AgentOutput JSON with a short thoughtSummary."
        if structured
        else "Return only the learner-facing Markdown explanation."
    )
    return [
        {
            "role": "system",
            "content": (
                "Explain the current page as primary evidence in Markdown. Adjacent pages "
                "are context only. Respect detailLevel and learnerMemoryDigest. Do not "
                f"invent facts. {output_instruction}"
            ),
        },
        {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
    ]


def qa_messages(
    context: AgentContext,
    mode: QaThreadMode,
    *,
    structured: bool = True,
) -> Sequence[Mapping[str, str]]:
    payload = {
        "question": context.event_payload.message,
        "page": context.session.current_page,
        "currentPageText": context.current_page_text,
        "previousPageText": context.previous_page_text,
        "nextPageText": context.next_page_text,
        "qaThreadMode": mode.value,
        "qaThreadDigest": context.qa_thread_digest if mode is QaThreadMode.FOLLOW_UP else None,
        "latestRepair": context.latest_repair,
        "learnerConfidence": context.learner_confidence,
        "learnerMemoryDigest": context.learner_memory_digest,
    }
    output_instruction = (
        "Return AgentOutput JSON with a short thoughtSummary."
        if structured
        else "Return only the learner-facing Markdown answer."
    )
    return [
        {
            "role": "system",
            "content": (
                "Answer from supplied page evidence in Markdown. START_NEW ignores old QA "
                "context; FOLLOW_UP must connect it. Use latestRepair only as follow-up "
                "context. If evidence is insufficient, clearly state the limitation. "
                f"{output_instruction}"
            ),
        },
        {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
    ]
