"""Turn cache opt-in retains all learning inputs, scope, policy and stream output."""

import json
from collections.abc import Mapping, Sequence
from copy import deepcopy

import httpx
import pytest
import respx

from edupilot_ai.factory import Dependencies, create_app
from edupilot_ai.llm.prompt_cache import stable_prefix_messages
from edupilot_ai.llm.xai import XAI_CHAT_COMPLETIONS_URL, XAI_RESPONSES_URL
from edupilot_ai.models.plan import AgentOutput, ToolName
from edupilot_ai.models.quiz import QuizType
from edupilot_ai.models.turn import DetailLevel, QaThreadMode, TurnRequest
from edupilot_ai.orchestration.context import AgentContext, ContextBuilder, PlanContext
from edupilot_ai.orchestration.prompt_cache import CacheStage, turn_prompt_cache
from edupilot_ai.orchestration.prompts import (
    explainer_messages,
    plan_messages,
    qa_messages,
    quiz_messages,
)
from edupilot_ai.settings import Settings
from tests.fakes import FakeLlm, FakeXaiFileClient
from tests.test_quiz_grading import add_section_quiz_context, make_quiz
from tests.test_turn_contract import make_explain_plan, make_plan
from tests.test_xai_llm_bridge import completion_response, responses_response, responses_stream


def stage_messages(
    context: AgentContext, stage: CacheStage, *, retry: bool = False, structured: bool = True
) -> Sequence[Mapping[str, str]]:
    if stage == "planner":
        return plan_messages(PlanContext.from_agent_context(context), retry=retry)
    if stage == "explainer":
        return explainer_messages(context, DetailLevel.DETAILED, structured=structured)
    if stage == "qa":
        return qa_messages(context, QaThreadMode.FOLLOW_UP, structured=structured)
    return quiz_messages(context, QuizType.OX)


@pytest.mark.parametrize("stage", ["planner", "explainer", "qa", "quiz"])
@pytest.mark.parametrize("structured", [False, True])
@pytest.mark.parametrize("retry", [False, True])
def test_every_original_learning_field_survives_layout(
    turn_payload: dict[str, object], stage: CacheStage, structured: bool, retry: bool
) -> None:
    payload = deepcopy(turn_payload)
    context_data = payload["context"]
    assert isinstance(context_data, dict)
    context_data.update(
        xaiFileId="private-file",
        currentPageText="현재 페이지 근거\n수식: x ≤ 10",
        previousPageText="이전 문맥",
        nextPageText="아직 학습하지 않은 다음 페이지",
        conversationSummary="요약보다 최근 질문을 우선해야 한다.",
        qaThreadDigest={"threadRef": "thread-private", "digest": "앞에서 물은 내용"},
        learnerMemoryDigest="예시를 선호하는 학습자",
        recentMessages=[{"senderType": "USER", "content": "최근 질문 원문"}],
        learnerConfidence="LOW",
    )
    if stage == "quiz":
        add_section_quiz_context(payload)
        payload["event"] = {"eventType": "QUIZ_TYPE_SELECTED", "payload": {"quizType": "OX"}}
    context = ContextBuilder().build(TurnRequest.model_validate(payload))
    original = stage_messages(context, stage, retry=retry, structured=structured)
    before = deepcopy(original)
    hint = turn_prompt_cache(context, stage)
    assert hint is not None
    prepared = stable_prefix_messages(original, hint)
    assert len(prepared) == 3
    assert (
        prepared[0] == original[0]
    )  # Includes injection defense and all scope/policy instructions.
    stable = json.loads(prepared[1]["content"])
    dynamic = json.loads(prepared[2]["content"])
    assert not stable.keys() & dynamic.keys()
    assert {**stable, **dynamic} == json.loads(original[1]["content"])
    assert original == before
    if stage == "planner":
        assert "turnId" not in stable
        assert dynamic["turnId"] == payload["turnId"]
        assert "eventPayload" in dynamic and "memory" in dynamic
    if stage == "qa":
        assert "question" not in stable
        assert dynamic["question"] == "편차가 뭔지 모르겠어"
        assert dynamic["qaThreadDigest"] == context_data["qaThreadDigest"]
        assert dynamic["conversationSummary"] == context_data["conversationSummary"]
    if stage == "quiz":
        assert stable["coverage"] == {"startPage": 1, "endPage": 3}
        assert context.quiz_context is not None
        assert stable["pageContext"] == [
            page.model_dump(by_alias=True) for page in context.quiz_context.pages
        ]
        assert "아직 학습하지 않은 다음 페이지" not in json.dumps(prepared, ensure_ascii=False)


@pytest.mark.parametrize("stage", ["planner", "explainer", "qa", "quiz"])
def test_new_turns_reuse_prefix_without_caching_old_question_or_memory(
    turn_payload: dict[str, object], stage: CacheStage
) -> None:
    first = ContextBuilder().build(TurnRequest.model_validate(turn_payload))
    second = first.model_copy(
        update={
            "turn_id": "another-turn",
            "event_payload": first.event_payload.model_copy(update={"message": "새로운 질문"}),
            "learner_memory_digest": "갱신한 개인화 정보",
            "recent_messages": [{"senderType": "USER", "content": "추가된 최근 질문"}],
        }
    )
    hint1, hint2 = turn_prompt_cache(first, stage), turn_prompt_cache(second, stage)
    assert hint1 is not None and hint2 is not None
    assert hint1 == hint2
    messages1 = stable_prefix_messages(stage_messages(first, stage), hint1)
    messages2 = stable_prefix_messages(stage_messages(second, stage), hint2)
    assert messages1[:2] == messages2[:2]
    assert messages1[2] != messages2[2]


def test_routing_key_is_opaque_session_scoped_and_stable_across_page_turn_changes(
    turn_payload: dict[str, object],
) -> None:
    first = ContextBuilder().build(TurnRequest.model_validate(turn_payload))
    baseline = turn_prompt_cache(first, "qa")
    assert baseline is not None
    assert len(baseline.key) == 64 and set(baseline.key) <= set("0123456789abcdef")
    for key in ("user_id", "session_id", "material_id"):
        changed = first.model_copy(update={"session": first.session.model_copy(update={key: 999})})
        assert turn_prompt_cache(changed, "qa") != baseline
    changed_file = first.model_copy(update={"xai_file_id": "private-file"})
    assert turn_prompt_cache(changed_file, "qa") != baseline
    assert turn_prompt_cache(first, "planner") != baseline
    next_page = first.model_copy(
        update={"session": first.session.model_copy(update={"current_page": 4}), "turn_id": "next"}
    )
    assert turn_prompt_cache(next_page, "qa") == baseline
    detached = changed_file.model_copy(
        update={
            "event_payload": first.event_payload.model_copy(update={"include_current_page": False})
        }
    )
    # A hidden file ID must not affect detached requests or cause attachment.
    assert turn_prompt_cache(detached, "qa") == baseline
    assert detached.attached_file_id is None


@pytest.mark.parametrize("event", ["EXPLAIN_CURRENT_PAGE", "USER_QUESTION", "QUIZ_TYPE_SELECTED"])
@pytest.mark.parametrize("streaming", [False, True])
async def test_enabled_factory_pipeline_preserves_actions_and_completed_usage(
    settings: Settings,
    turn_payload: dict[str, object],
    auth_headers: dict[str, str],
    respx_mock: respx.MockRouter,
    event: str,
    streaming: bool,
) -> None:
    payload = deepcopy(turn_payload)
    context_data = payload["context"]
    assert isinstance(context_data, dict)
    context_data["xaiFileId"] = "file-test"
    reply = AgentOutput(markdown="현재 페이지의 답변입니다.")
    provider_responses: list[httpx.Response] = []
    chat_route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL)
    if event == "EXPLAIN_CURRENT_PAGE":
        payload["event"] = {"eventType": event, "payload": {"detailLevel": "DETAILED"}}
        provider_responses.append(
            httpx.Response(
                200,
                json=responses_response(
                    content=make_explain_plan(propose_quiz=True).model_dump_json(by_alias=True)
                ),
            )
        )
    elif event == "USER_QUESTION":
        plan = make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "START_NEW", "threadRef": None},
            "질문 답변",
        )
        chat_route.mock(
            return_value=httpx.Response(
                200, json=completion_response(content=plan.model_dump_json(by_alias=True))
            )
        )
    else:
        payload["event"] = {"eventType": event, "payload": {"quizType": "OX"}}
    if event == "QUIZ_TYPE_SELECTED":
        provider_responses.append(
            httpx.Response(
                200,
                json=responses_response(
                    content=make_quiz(QuizType.OX).model_dump_json(by_alias=True)
                ),
            )
        )
    elif streaming:
        provider_responses.append(httpx.Response(200, text=responses_stream()))
    else:
        provider_responses.append(
            httpx.Response(
                200, json=responses_response(content=reply.model_dump_json(by_alias=True))
            )
        )
    responses_route = respx_mock.post(XAI_RESPONSES_URL).mock(side_effect=provider_responses)
    app = create_app(
        settings=settings.model_copy(
            update={
                "edupilot_prompt_cache_layout_enabled": True,
                "edupilot_prompt_cache_routing_enabled": True,
            }
        ),
        dependencies=Dependencies(file_client=FakeXaiFileClient()),
    )
    headers = {**auth_headers, **({"Accept": "application/x-ndjson"} if streaming else {})}
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app), base_url="http://test"
        ) as client:
            response = await client.post("/internal/ai/turn", json=payload, headers=headers)
    assert response.status_code == 200
    if streaming:
        events = [json.loads(line) for line in response.text.splitlines()]
        assert events[0] == {"type": "status", "stage": "PLANNING"}
        assert events[-1]["type"] == "completed"
        assert all("usage" not in item and "result" not in item for item in events[:-1])
        result = events[-1]["result"]
        assert "".join(
            item["text"] for item in events if item["type"] == "content_delta"
        ) == "".join(item["content"] for item in result["messages"])
    else:
        result = response.json()
    assert result["usage"]["model"] == settings.model_name
    assert all(action["status"] == "SUCCESS" for action in result["actionsExecuted"])
    if event == "EXPLAIN_CURRENT_PAGE":
        assert len(result["uiActions"]) == 1  # Planner still judges whether a quiz is useful.
        assert result["statePatch"]["pageStatus"] == "EXPLAINED"
    elif event == "QUIZ_TYPE_SELECTED":
        assert result["quiz"]["quizType"] == "OX"
        assert result["quiz"]["questionCount"] == 5
        assert result["quiz"]["coverage"] == {"startPage": 3, "endPage": 3}
    assert responses_route.call_count + chat_route.call_count == (
        1 if event == "QUIZ_TYPE_SELECTED" else 2
    )
    for call in responses_route.calls:
        body = json.loads(call.request.content)
        assert body["input"][1]["content"] == [{"type": "input_file", "file_id": "file-test"}]
        assert body["prompt_cache_key"] and body["store"] is False
        assert body["max_output_tokens"] == 16384
    for call in chat_route.calls:
        assert call.request.headers.get("x-grok-conv-id")


async def test_detached_question_has_cache_hint_but_never_attaches_file(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    turn_payload: dict[str, object],
    auth_headers: dict[str, str],
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "USER_QUESTION",
        "payload": {"message": "일반 지식 질문", "includeCurrentPage": False},
    }
    context = payload["context"]
    assert isinstance(context, dict)
    context.update(
        xaiFileId="must-not-be-used", currentPageText=None, previousPageText=None, nextPageText=None
    )
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION, {"qaThreadMode": "START_NEW", "threadRef": None}, "질문 답변"
        ),
        AgentOutput(markdown="일반 지식 답변"),
    )
    response = await client.post("/internal/ai/turn", json=payload, headers=auth_headers)
    assert response.status_code == 200
    assert fake_llm.file_attachments == [(), ()]
    assert all(hint is not None for hint in fake_llm.prompt_caches)


def test_cache_environment_switches_default_off_and_can_be_set_independently(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    assert not settings.edupilot_prompt_cache_layout_enabled
    assert not settings.edupilot_prompt_cache_routing_enabled
    monkeypatch.setenv("EDUPILOT_PROMPT_CACHE_LAYOUT_ENABLED", "true")
    monkeypatch.setenv("EDUPILOT_PROMPT_CACHE_ROUTING_ENABLED", "false")
    configured = Settings(
        _env_file=None,
        edupilot_internal_token=settings.edupilot_internal_token,
        xai_api_key=settings.xai_api_key,
    )
    assert configured.edupilot_prompt_cache_layout_enabled
    assert not configured.edupilot_prompt_cache_routing_enabled
