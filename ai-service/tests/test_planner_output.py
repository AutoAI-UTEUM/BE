"""Compact output removes echoes, not learning decisions or Policy gates."""

import json
from copy import deepcopy
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import LlmBridgeError, LlmUsage
from edupilot_ai.models.plan import AgentOutput, ToolName, TurnPlan
from edupilot_ai.models.turn import Adjustment, TurnRequest
from edupilot_ai.orchestration.context import AgentContext, ContextBuilder
from edupilot_ai.orchestration.orchestrator import Orchestrator
from edupilot_ai.orchestration.planner_output import (
    PlannerAction,
    PlannerOutput,
    expand_planner_output,
)
from edupilot_ai.orchestration.policy import PolicyVerifier, PolicyViolation
from edupilot_ai.orchestration.timing import TurnDeadline
from edupilot_ai.settings import Settings
from tests.fakes import FakeLlm
from tests.planner_fixtures import planner_output
from tests.test_turn_contract import make_explain_plan, make_plan


@pytest.mark.parametrize("propose_quiz", [False, True])
def test_explanation_restores_only_fixed_fields_and_preserves_every_decision(
    turn_payload: dict[str, object], propose_quiz: bool
) -> None:
    raw = TurnRequest.model_validate(turn_payload).model_dump(mode="json", by_alias=True)
    raw["event"] = {"eventType": "EXPLAIN_CURRENT_PAGE", "payload": {"detailLevel": "DETAILED"}}
    raw["context"]["xaiFileId"] = "file-planner-test"
    context = ContextBuilder().build(TurnRequest.model_validate(raw))
    full = make_explain_plan(propose_quiz=propose_quiz)
    decision = planner_output(full)
    original = decision.model_dump(mode="json")

    restored = expand_planner_output(decision, context)
    verified, adjustments = PolicyVerifier().verify(restored, context)

    assert verified == full
    assert adjustments == []
    assert decision.model_dump(mode="json") == original
    assert all(action.args == {} for action in decision.actions)
    assert len(decision.model_dump_json()) < len(full.model_dump_json())
    fields = PlannerOutput.model_json_schema(by_alias=True)["properties"]
    assert "schemaVersion" not in fields and "memoryWrite" not in fields
    action_fields = PlannerAction.model_json_schema(by_alias=True)["properties"]
    assert set(action_fields) == {"tool", "args"}


@pytest.mark.parametrize("mode", ["START_NEW", "FOLLOW_UP", "FOLLOWUP", "follow-up"])
def test_qa_mode_remains_a_model_decision_and_reference_comes_only_from_snapshot(
    turn_payload: dict[str, object], mode: str
) -> None:
    request = TurnRequest.model_validate(turn_payload)
    request.context.qa_thread_digest = {"threadRef": "trusted-thread", "digest": "직전 대화"}
    context = ContextBuilder().build(request)
    decision = planner_output(
        make_plan(ToolName.ANSWER_QUESTION, {"qaThreadMode": mode}, "학습자가 선택한 주제")
    )
    decision.propose_note = True
    decision.stop = "완료 후 대기"
    decision.pedagogy_policy.allow_direct_answer = False

    restored = expand_planner_output(decision, context)
    verified, _ = PolicyVerifier().verify(restored, context)

    assert verified.actions[0].args == {
        "qaThreadMode": "START_NEW" if mode == "START_NEW" else "FOLLOW_UP",
        "threadRef": None if mode == "START_NEW" else "trusted-thread",
    }
    assert verified.turn_goal == decision.turn_goal
    assert verified.reason == decision.reason
    assert verified.pedagogy_policy == decision.pedagogy_policy
    assert verified.propose_note is True and verified.stop == decision.stop
    assert "threadRef" not in decision.actions[0].args


@pytest.mark.parametrize(
    ("tool", "args"),
    [
        (ToolName.EXPLAIN_PAGE, {"page": 99}),
        (ToolName.PROMPT_BINARY_DECISION, {"contentMarkdown": "임의 버튼"}),
        (ToolName.REPAIR_MISCONCEPTION, {"diagnosisId": 99}),
        (ToolName.GENERATE_QUIZ_OX, {"quizType": "MCQ"}),
        (ToolName.ANSWER_QUESTION, {"qaThreadMode": "FOLLOW_UP", "threadRef": "forged"}),
        (ToolName.ANSWER_QUESTION, {}),
    ],
)
def test_compact_output_rejects_echo_injection(tool: ToolName, args: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        PlannerAction(tool=tool, args=args)


@pytest.mark.parametrize(
    ("tool", "args", "reason"),
    [
        (ToolName.GRADE_OPEN_RESPONSE, {}, "pipeline-only"),
        (ToolName.PROMPT_BINARY_DECISION, {}, "tool does not match event"),
        (ToolName.ANSWER_QUESTION, {"qaThreadMode": "FOLLOW_UP"}, "requires qaThreadDigest"),
        (ToolName.ANSWER_QUESTION, {"qaThreadMode": "INVALID"}, "invalid qaThreadMode"),
    ],
)
def test_expansion_does_not_approve_invalid_decisions(
    turn_payload: dict[str, object], tool: ToolName, args: dict[str, Any], reason: str
) -> None:
    context = ContextBuilder().build(TurnRequest.model_validate(turn_payload))
    decision = planner_output(make_plan(tool, args, "정책 거부 사례"))
    with pytest.raises(PolicyViolation, match=reason):
        PolicyVerifier().verify(expand_planner_output(decision, context), context)


@pytest.mark.parametrize("streaming", [False, True])
@pytest.mark.parametrize("event", ["EXPLAIN_CURRENT_PAGE", "USER_QUESTION"])
async def test_expanded_output_is_always_verified_before_dispatch(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    monkeypatch: pytest.MonkeyPatch,
    streaming: bool,
    event: str,
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": event,
        "payload": {"detailLevel": "DETAILED"}
        if event == "EXPLAIN_CURRENT_PAGE"
        else {"message": "방금 개념을 다시 비교해줘"},
    }
    snapshot = payload["context"]
    assert isinstance(snapshot, dict)
    snapshot["xaiFileId"] = "file-planner-test"
    full = (
        make_explain_plan(propose_quiz=True)
        if event == "EXPLAIN_CURRENT_PAGE"
        else make_plan(ToolName.ANSWER_QUESTION, {"qaThreadMode": "START_NEW"}, "답변")
    )
    fake_llm.queue(planner_output(full))
    if streaming:
        fake_llm.queue_text_stream("근거를 ", "보존한 답변")
    else:
        fake_llm.queue(AgentOutput(markdown="근거를 보존한 답변"))
    seen: list[TurnPlan] = []
    verify = PolicyVerifier.verify

    def record_verify(
        self: PolicyVerifier, plan: TurnPlan, context: AgentContext
    ) -> tuple[TurnPlan, list[Adjustment]]:
        seen.append(plan.model_copy(deep=True))
        return verify(self, plan, context)

    monkeypatch.setattr(PolicyVerifier, "verify", record_verify)
    response = await client.post(
        "/internal/ai/turn",
        json=payload,
        headers={**auth_headers, "Accept": "application/x-ndjson"} if streaming else auth_headers,
    )

    assert response.status_code == 200
    assert len(seen) == 1
    assert seen[0].schema_version == "1.0" and seen[0].memory_write is None
    assert all(action.type == "CALL_TOOL" for action in seen[0].actions)
    assert fake_llm.response_models[0] is PlannerOutput
    assert len(fake_llm.calls) + len(fake_llm.stream_calls) == 2
    if streaming:
        events = [json.loads(line) for line in response.text.splitlines()]
        result = events[-1]["result"]
        assert events[0] == {"type": "status", "stage": "PLANNING"}
        assert events[-1]["type"] == "completed"
        assert (
            "".join(e["text"] for e in events if e["type"] == "content_delta")
            == (result["messages"][0]["content"])
        )
        assert all("usage" not in e for e in events[:-1])
    else:
        result = response.json()
    assert result["turnGoal"] == full.turn_goal
    assert [a["actionId"] for a in result["actionsExecuted"]] == [
        f"action-{i}" for i in range(1, len(full.actions) + 1)
    ]
    assert result["usage"] is not None
    assert len(result["uiActions"]) == (1 if event == "EXPLAIN_CURRENT_PAGE" else 0)


async def test_schema_retry_keeps_remaining_deadline_and_combines_usage(
    turn_payload: dict[str, object], settings: Settings
) -> None:
    usage = LlmUsage("grok-test", 10, 2, 1, cost_usd_ticks=100)
    llm = FakeLlm([LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False, usage=usage)])
    llm.queue_completion(
        planner_output(make_plan(ToolName.ANSWER_QUESTION, {"qaThreadMode": "START_NEW"}, "답변")),
        usage,
    )
    readings = iter([100.0, 160.0])
    deadline = TurnDeadline(expires_at=180.0, clock=lambda: next(readings))
    context = ContextBuilder().build(TurnRequest.model_validate(turn_payload))

    result = await Orchestrator(llm=llm, profile=settings.orchestrator_llm_profile).create_plan(
        context, deadline
    )

    assert llm.timeouts == [80, 20]
    assert result.attempts == 2
    assert result.usage.input_tokens == 20 and result.usage.cost_usd_ticks == 200
    assert "regenerate exactly once" not in llm.calls[0][0][0]["content"]
    assert "regenerate exactly once" in llm.calls[1][0][0]["content"]
