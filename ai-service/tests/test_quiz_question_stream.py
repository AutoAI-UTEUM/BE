"""Offline provisional previews: timing, privacy, failure and cancellation gates."""

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from copy import deepcopy
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import LlmBridgeError, LlmTextDelta, LlmTextStreamItem, LlmUsage
from edupilot_ai.models.quiz import QuizCoverage, QuizType
from edupilot_ai.models.quiz_preview import QuizQuestionStreamEvent
from edupilot_ai.models.turn import TurnRequest
from edupilot_ai.orchestration.quiz_stream import QuizStreamParser
from edupilot_ai.settings import Settings
from tests.fakes import FakeLlm, FakeStreamPause
from tests.test_quiz_grading import make_quiz
from tests.test_turn_stream import make_service


def stream_payload(payload: dict[str, object], quiz_type: QuizType = QuizType.OX) -> dict[str, Any]:
    result: dict[str, Any] = deepcopy(payload)
    result["event"] = {"eventType": "QUIZ_TYPE_SELECTED", "payload": {"quizType": quiz_type.value}}
    result["capabilities"] = {"quizQuestionStream": True}
    return result


def quiz_chunks(quiz_type: QuizType) -> list[str]:
    raw = make_quiz(quiz_type).model_dump(mode="json", by_alias=True)
    questions = raw.pop("questions")
    prefix = json.dumps(raw, ensure_ascii=False)[:-1] + ', "questions": ['
    return [
        prefix + json.dumps(questions[0], ensure_ascii=False),
        *("," + json.dumps(question, ensure_ascii=False) for question in questions[1:]),
        "]}",
    ]


@pytest.mark.parametrize("quiz_type", list(QuizType))
async def test_one_provider_stream_five_public_previews_and_final_private_result(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    quiz_type: QuizType,
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO)
    payload = stream_payload(turn_payload, quiz_type)
    payload["context"]["xaiFileId"] = "file-quiz-stream"
    fake_llm.queue_text_stream(*quiz_chunks(quiz_type), usage=LlmUsage("test-model", 11, 22, 3, 77))
    response = await client.post(
        "/internal/ai/turn",
        json=payload,
        headers={**auth_headers, "Accept": "application/x-ndjson"},
    )
    events = [json.loads(line) for line in response.text.splitlines()]
    assert response.status_code == 200
    assert events[0] == {"type": "status", "stage": "PLANNING"}
    assert events[-1]["type"] == "completed"
    previews = [e for e in events if e["type"] == "quiz_question"]
    assert len(previews) == 5
    assert [e["questionIndex"] for e in previews] == [1, 2, 3, 4, 5]
    for event in previews:
        assert event["provisional"] is True
        assert event["questionCount"] == 5
        allowed = {"questionId", "questionText", "points"}
        if quiz_type is QuizType.MCQ:
            allowed.add("choices")
        assert set(event["question"]) == allowed
    assert not any(e["type"] == "content_delta" for e in events)
    assert all("usage" not in e for e in events[:-1])
    result = events[-1]["result"]
    assert result["quiz"] == make_quiz(quiz_type).model_dump(mode="json", by_alias=True)
    for preview, final in zip(previews, result["quiz"]["questions"], strict=True):
        assert preview["question"] == {key: final[key] for key in preview["question"]}
    assert result["usage"]["cost_usd_ticks"] == 77
    assert result["messages"] == []
    assert fake_llm.calls == [] and len(fake_llm.stream_calls) == 1
    assert fake_llm.stream_file_attachments[0][0].file_id == "file-quiz-stream"
    assert "1번 문제" not in caplog.text and "기준 답안" not in caplog.text
    assert any(r.getMessage() == "turn stream completed" for r in caplog.records)
    ready = [r for r in caplog.records if r.getMessage() == "turn first quiz question available"]
    assert len(ready) == 1 and getattr(ready[0], "firstQuestionMs", -1) >= 0


@pytest.mark.parametrize("quiz_type", list(QuizType))
@pytest.mark.parametrize("chunk_size", [1, 7, 101])
def test_incremental_parser_handles_escaped_braces_and_multibyte_text(
    quiz_type: QuizType,
    chunk_size: int,
) -> None:
    raw = make_quiz(quiz_type).model_dump(mode="json", by_alias=True)
    raw["questions"][0]["questionText"] = '문자열 "},\\"questions":[" 와 줄바꿈\n은 경계가 아니다'
    text = json.dumps(raw, ensure_ascii=False)
    parser = QuizStreamParser(quiz_type, QuizCoverage(start_page=3, end_page=3))
    previews = []
    for offset in range(0, len(text), chunk_size):
        previews.extend(parser.feed(text[offset : offset + chunk_size]))
    assert len(previews) == 5
    assert parser.finish().questions[0].question_text == raw["questions"][0]["questionText"]


@pytest.mark.parametrize(
    "defect",
    [
        "incomplete",
        "trailing",
        "duplicate_key",
        "duplicate_id",
        "wrong_type",
        "range",
        "four",
        "six",
        "private_unknown",
        "trailing_comma",
        "metadata_after",
        "nan",
        "infinity",
        "infinity_string",
    ],
)
def test_bad_stream_never_becomes_a_complete_quiz(defect: str) -> None:
    raw = make_quiz(QuizType.OX).model_dump(mode="json", by_alias=True)
    if defect == "duplicate_id":
        raw["questions"][1]["questionId"] = "q-1"
    elif defect == "wrong_type":
        raw["quizType"] = "MCQ"
    elif defect == "range":
        raw["coverage"]["endPage"] = 4
    elif defect == "four":
        raw["questions"].pop()
    elif defect == "six":
        raw["questions"].append({**raw["questions"][0], "questionId": "q-6"})
    elif defect == "private_unknown":
        raw["questions"][0]["secretAnswer"] = "should reject"
    elif defect == "metadata_after":
        raw["title"] = raw.pop("title")
    text = json.dumps(raw)
    if defect == "incomplete":
        text = text[:-2]
    elif defect == "trailing":
        text += "another object"
    elif defect == "duplicate_key":
        text = text.replace('"points": 10.0', '"points": 10.0, "points": 10.0', 1)
    elif defect == "trailing_comma":
        text = text[:-2] + ",]}"
    elif defect == "nan":
        text = text.replace('"points": 10.0', '"points": NaN', 1)
    elif defect == "infinity":
        text = text.replace('"points": 10.0', '"points": 1e9999', 1)
    elif defect == "infinity_string":
        text = text.replace('"points": 10.0', '"points": "Infinity"', 1)
    parser = QuizStreamParser(QuizType.OX, QuizCoverage(start_page=3, end_page=3))
    with pytest.raises(LlmBridgeError) as failure:
        parser.feed(text)
        parser.finish()
    assert failure.value.category is ErrorCategory.SCHEMA


async def test_provider_failure_after_preview_has_error_not_completed(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO)
    fake_llm.queue_text_stream(
        quiz_chunks(QuizType.OX)[0],
        LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True),
    )
    response = await client.post(
        "/internal/ai/turn",
        json=stream_payload(turn_payload),
        headers={**auth_headers, "Accept": "application/x-ndjson"},
    )
    events = [json.loads(line) for line in response.text.splitlines()]
    assert len([e for e in events if e["type"] == "quiz_question"]) == 1
    assert events[-1]["type"] == "error" and events[-1]["code"] == "AI_SERVICE_TIMEOUT"
    assert not any(e["type"] == "completed" for e in events)
    assert len(fake_llm.stream_calls) == 1
    assert any(getattr(r, "status", None) == "FAILED" for r in caplog.records)


async def test_first_question_does_not_wait_for_rest_and_cancel_closes_provider(
    settings: Settings,
    turn_payload: dict[str, object],
) -> None:
    closed = asyncio.Event()
    reached_wait = asyncio.Event()

    class PausedFakeLlm(FakeLlm):
        async def complete_text_stream(self, **kwargs: Any) -> AsyncIterator[LlmTextStreamItem]:
            try:
                yield LlmTextDelta(text=quiz_chunks(QuizType.OX)[0])
                reached_wait.set()
                await asyncio.Event().wait()
            finally:
                closed.set()

    service = make_service(PausedFakeLlm(), settings)
    loop_errors: list[dict[str, Any]] = []
    loop = asyncio.get_running_loop()
    previous_handler = loop.get_exception_handler()
    loop.set_exception_handler(lambda _loop, info: loop_errors.append(info))
    events = service.stream_events(TurnRequest.model_validate(stream_payload(turn_payload)))
    try:
        async with asyncio.timeout(1):
            while not isinstance(await anext(events), QuizQuestionStreamEvent):
                pass
            # The source cannot even request chunk 2 until this preview has been consumed.
            assert not reached_wait.is_set()
            pending = asyncio.create_task(anext(events))
            await reached_wait.wait()
            pending.cancel()
            with pytest.raises(asyncio.CancelledError):
                await pending
            await events.aclose()
            assert closed.is_set()
            await asyncio.sleep(0)
            assert loop_errors == []
    finally:
        loop.set_exception_handler(previous_handler)


async def test_preview_stream_keeps_heartbeats_while_waiting(
    fake_llm: FakeLlm,
    settings: Settings,
    turn_payload: dict[str, object],
) -> None:
    chunks = quiz_chunks(QuizType.OX)
    fake_llm.queue_text_stream(chunks[0], FakeStreamPause(0.035), *chunks[1:])
    service = make_service(fake_llm, settings, heartbeat_interval_seconds=0.005)
    events = [
        json.loads(line)
        async for line in service.stream_ndjson(
            TurnRequest.model_validate(stream_payload(turn_payload))
        )
    ]
    assert events[-1]["type"] == "completed"
    assert any(e["type"] == "heartbeat" for e in events)


async def test_json_request_with_capability_keeps_full_artifact_path(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(make_quiz(QuizType.OX))
    response = await client.post(
        "/internal/ai/turn", json=stream_payload(turn_payload), headers=auth_headers
    )
    assert response.status_code == 200
    assert response.json()["quiz"]["questionCount"] == 5
    assert len(fake_llm.calls) == 1 and not fake_llm.stream_calls


def test_unfinished_json_is_bounded() -> None:
    parser = QuizStreamParser(QuizType.OX, QuizCoverage(start_page=3, end_page=3))
    with pytest.raises(LlmBridgeError):
        parser.feed('{"title":"' + "x" * 1_048_576)


@pytest.mark.parametrize("capability", ["qaQuizProposal", "quizQuestionStream"])
@pytest.mark.parametrize("value", ["true", 1])
async def test_capability_is_explicit_boolean_not_truthy_value(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    capability: str,
    value: object,
) -> None:
    payload = stream_payload(turn_payload)
    payload["capabilities"] = {capability: value}
    response = await client.post("/internal/ai/turn", json=payload, headers=auth_headers)
    assert response.status_code == 422
    assert not fake_llm.calls and not fake_llm.stream_calls


async def test_wrong_event_rejects_quiz_stream_before_provider(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload["capabilities"] = {"quizQuestionStream": True}
    response = await client.post("/internal/ai/turn", json=payload, headers=auth_headers)
    assert response.status_code == 422
    assert not fake_llm.calls and not fake_llm.stream_calls


def test_openapi_documents_optional_flags_and_negotiated_stream(app: FastAPI) -> None:
    schema = app.openapi()
    models = schema["components"]["schemas"]
    request = models["TurnRequest"]
    assert "capabilities" in request["properties"] and "capabilities" not in request["required"]
    assert models["TurnCapabilities"]["properties"]["quizQuestionStream"]["default"] is False
    response = schema["paths"]["/internal/ai/turn"]["post"]["responses"]["200"]
    assert "application/json" in response["content"]
    assert "application/x-ndjson" in response["content"]
    assert "quiz_question" in response["description"]
