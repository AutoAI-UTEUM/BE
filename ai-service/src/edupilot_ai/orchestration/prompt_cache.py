"""Session-scoped cache hints for measured turn stages, without changing policy."""

import hashlib
import json
from typing import Literal

from edupilot_ai.llm.bridge import LlmPromptCache
from edupilot_ai.models.turn import EventType
from edupilot_ai.orchestration.context import AgentContext

type CacheStage = Literal["planner", "explainer", "qa", "quiz"]

_STABLE_FIELDS: dict[CacheStage, tuple[str, ...]] = {
    "planner": (
        "hasMaterialAttachment",
        "pageTextPreview",
        "hasPreviousPageText",
        "hasNextPageText",
    ),
    "explainer": ("page", "currentPageText", "previousPageText", "nextPageText"),
    "qa": ("includeCurrentPage", "page", "currentPageText", "previousPageText", "nextPageText"),
    "quiz": ("pageContext", "referenceContext", "coverage", "currentPage"),
}


def turn_prompt_cache(context: AgentContext, stage: CacheStage) -> LlmPromptCache | None:
    if stage == "planner" and context.event_type not in {
        EventType.EXPLAIN_CURRENT_PAGE,
        EventType.USER_QUESTION,
    }:
        return None
    # Neither questions nor per-turn IDs enter the routing key. Isolate sessions,
    # learners, materials, file replacements and roles; never log the key itself.
    identity = json.dumps(
        [
            "turn-prefix-v1",
            context.session.user_id,
            context.session.session_id,
            context.session.material_id,
            context.attached_file_id,
            stage,
        ],
        separators=(",", ":"),
    )
    return LlmPromptCache(
        key=hashlib.sha256(identity.encode()).hexdigest(),
        stable_user_fields=_STABLE_FIELDS[stage],
    )
