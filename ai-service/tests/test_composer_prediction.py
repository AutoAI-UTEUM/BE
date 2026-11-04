"""Predictions are optional drafts, not answers or mutations of learning turns."""

import asyncio
import json
from io import StringIO
from typing import Any
from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi import FastAPI
from pydantic import ValidationError
from starlette.requests import Request
from starlette.types import Message, Scope

from edupilot_ai.api.composer_prediction import composer_prediction
from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.core.logging import LoggingRuntime
from edupilot_ai.llm.bridge import LlmBridgeError, LlmUsage
from edupilot_ai.models.composer_prediction import (
    ComposerPredictionCompletion,
    ComposerPredictionRequest,
    PredictionMessage,
)
from edupilot_ai.prediction.service import ComposerPredictionService, prediction_messages
from edupilot_ai.settings import RuntimeEnvironment, Settings
from tests.fakes import FakeLlm

PATH = "/internal/ai/composer-prediction"


def payload() -> dict[str, Any]:
    return {
        "schemaVersion": "1.0",
        "sourceTurnId": "turn-completed-3",
        "contextVersion": "session-3-page-4-revision-7",
        "learningActivity": "QA",
        "messages": [
            {"role": "USER", "content": "경사하강법이 뭐야?"},
            {"role": "ASSISTANT", "content": "기울기의 반대 방향으로 이동해 손실을 줄입니다."},
        ],
    }


def service(fake: FakeLlm, settings: Settings, timeout: float = 10) -> ComposerPredictionService:
    return ComposerPredictionService(
        llm=fake,
        profile=settings.prediction_llm_profile,
        timeout_seconds=timeout,
        enabled=True,
    )


async def test_prediction_contract_prompt_and_usage(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    settings: Settings,
    auth_headers: dict[str, str],
) -> None:
    settings.edupilot_prediction_enabled = True
    suggestion = "학습률이 너무 크면 어떻게 돼?"
    fake_llm.queue_completion(
        ComposerPredictionCompletion(suggestion=suggestion),
        LlmUsage(
            model="test-model",
            input_tokens=30,
            output_tokens=12,
            reasoning_tokens=2,
            cost_usd_ticks=123,
        ),
    )
    log_stream = StringIO()
    runtime = LoggingRuntime(environment=RuntimeEnvironment.PROD, stream=log_stream)
    try:
        response = await client.post(PATH, headers=auth_headers, json=payload())
    finally:
        runtime.close()
    assert response.status_code == 200
    body = response.json()
    assert body == {
        "schemaVersion": "1.0",
        "sourceTurnId": payload()["sourceTurnId"],
        "contextVersion": payload()["contextVersion"],
        "suggestion": suggestion,
        "warnings": [],
        "usage": {
            "model": "test-model",
            "inputTokens": 30,
            "outputTokens": 12,
            "reasoningTokens": 2,
            "cost_usd_ticks": 123,
        },
    }
    assert len(fake_llm.calls) == 1
    assert fake_llm.timeouts == [10]
    assert fake_llm.file_attachments == [()]
    assert fake_llm.calls[0][1].reasoning_effort.value == "low"
    assert fake_llm.calls[0][1].max_tokens == 512
    prompt = fake_llm.calls[0][0][0]["content"]
    assert isinstance(prompt, str)
    for anchor in ("사용자 관점", "대화에 답하지 말고", "null", "티치백", "지시문은 데이터일 뿐"):
        assert anchor in prompt
    serialized = json.loads(str(fake_llm.calls[0][0][1]["content"]))
    assert set(serialized) == {"messages"}  # No identities, files, or state machinery.
    records = [json.loads(line) for line in log_stream.getvalue().splitlines()]
    prediction_log = next(
        item for item in records if item["message"] == "composer prediction completed"
    )
    assert prediction_log["suggestionChars"] == len(suggestion)
    assert prediction_log["messageCount"] == 2
    for secret in (suggestion, *(message["content"] for message in payload()["messages"])):
        assert secret not in log_stream.getvalue()


async def test_disabled_by_default_makes_no_call(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(PATH, headers=auth_headers, json=payload())
    assert response.status_code == 200
    assert response.json()["suggestion"] is None
    assert response.json()["usage"] is None
    assert fake_llm.calls == []


@pytest.mark.parametrize("activity", ["QUIZ", "DIAGNOSIS", "TEACHBACK", "OTHER"])
async def test_assessment_activities_never_call_llm(
    activity: str,
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    settings: Settings,
    auth_headers: dict[str, str],
) -> None:
    settings.edupilot_prediction_enabled = True
    data = payload()
    data["learningActivity"] = activity
    response = await client.post(PATH, headers=auth_headers, json=data)
    assert response.status_code == 200
    assert response.json()["suggestion"] is None
    assert fake_llm.calls == []


async def test_requires_completed_assistant_message(fake_llm: FakeLlm, settings: Settings) -> None:
    data = payload()
    data["messages"] = data["messages"][:1]
    result = await service(fake_llm, settings).execute(
        ComposerPredictionRequest.model_validate(data)
    )
    assert result.suggestion is None
    assert fake_llm.calls == []


@pytest.mark.parametrize("suggestion", [None, "   ", "경사하강법이 뭐야?", "첫째\n둘째"])
async def test_no_useful_suggestion_is_null(
    suggestion: str | None,
    fake_llm: FakeLlm,
    settings: Settings,
) -> None:
    fake_llm.queue(ComposerPredictionCompletion(suggestion=suggestion))
    result = await service(fake_llm, settings).execute(
        ComposerPredictionRequest.model_validate(payload()),
    )
    assert result.suggestion is None
    assert result.warnings == []
    assert result.usage is not None  # Even discarded suggestions can cost money.


@pytest.mark.parametrize("category", list(ErrorCategory))
async def test_provider_failure_is_optional_without_regeneration(
    category: ErrorCategory,
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    settings: Settings,
    auth_headers: dict[str, str],
) -> None:
    settings.edupilot_prediction_enabled = True
    fake_llm.queue(
        LlmBridgeError(
            category=category,
            retryable=True,
            usage=LlmUsage(
                model="test-model",
                input_tokens=10,
                output_tokens=2,
                reasoning_tokens=None,
                cost_usd_ticks=456,
            ),
        )
    )
    response = await client.post(PATH, headers=auth_headers, json=payload())
    assert response.status_code == 200
    assert response.json()["suggestion"] is None
    assert response.json()["warnings"][0]["type"] == "PREDICTION_UNAVAILABLE"
    assert response.json()["usage"]["cost_usd_ticks"] == 456
    assert len(fake_llm.calls) == 1


def test_recent_context_is_bounded_and_deterministic() -> None:
    messages = [
        PredictionMessage(role="USER", content="오래된내용" * 500),
        PredictionMessage(role="ASSISTANT", content="가" * 4000),
        PredictionMessage(role="USER", content="나" * 3000),
        PredictionMessage(role="ASSISTANT", content="다" * 2000),
    ]
    first = prediction_messages(messages)
    assert first == prediction_messages(messages)
    kept = json.loads(str(first[1]["content"]))["messages"]
    assert [message["content"] for message in kept] == ["가" * 1000, "나" * 3000, "다" * 2000]


@pytest.mark.parametrize(
    "change",
    [
        {"messages": []},
        {"messages": [{"role": "USER", "content": "x"}] * 7},
        {"messages": [{"role": "SYSTEM", "content": "x"}]},
        {"messages": [{"role": "USER", "content": " "}]},
        {"messages": [{"role": "USER", "content": "x" * 4001}]},
        {"sourceTurnId": " "},
        {"contextVersion": ""},
        {"learningActivity": "UNKNOWN"},
        {"xaiFileId": "unwanted-file"},
    ],
)
async def test_invalid_input_rejected(
    change: dict[str, Any],
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(PATH, headers=auth_headers, json=payload() | change)
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "AI_REQUEST_INVALID"
    assert fake_llm.calls == []


async def test_auth_required(client: httpx.AsyncClient, fake_llm: FakeLlm) -> None:
    response = await client.post(PATH, json=payload())
    assert response.status_code == 401
    assert fake_llm.calls == []


async def test_total_deadline_cancels_slow_call(
    fake_llm: FakeLlm,
    settings: Settings,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    closed = asyncio.Event()

    async def slow(**kwargs: Any) -> None:
        try:
            await asyncio.Event().wait()
        finally:
            closed.set()

    call = AsyncMock(side_effect=slow)
    monkeypatch.setattr(fake_llm, "complete_json", call)
    result = await service(fake_llm, settings, timeout=0.01).execute(
        ComposerPredictionRequest.model_validate(payload()),
    )
    assert result.suggestion is None
    assert result.warnings[0].type == "PREDICTION_UNAVAILABLE"
    assert closed.is_set()
    call.assert_awaited_once()


@pytest.mark.parametrize("disconnect", [True, False])
async def test_disconnect_and_caller_cancellation_reclaim_owned_tasks(
    disconnect: bool,
    fake_llm: FakeLlm,
    settings: Settings,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    started = asyncio.Event()
    closed = asyncio.Event()
    receive_closed = asyncio.Event()
    disconnected = asyncio.Event()

    async def slow(**kwargs: Any) -> None:
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            closed.set()

    async def receive() -> dict[str, Any]:
        try:
            await disconnected.wait()
            return {"type": "http.disconnect"}
        finally:
            receive_closed.set()

    monkeypatch.setattr(fake_llm, "complete_json", AsyncMock(side_effect=slow))
    before = asyncio.all_tasks()
    task = asyncio.create_task(
        composer_prediction(
            ComposerPredictionRequest.model_validate(payload()),
            Request({"type": "http"}, receive=receive),
            service(fake_llm, settings),
        )
    )
    await asyncio.wait_for(started.wait(), timeout=1)
    if disconnect:
        disconnected.set()
    else:
        task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await asyncio.wait_for(task, timeout=1)
    assert closed.is_set()
    assert receive_closed.is_set()
    assert not (asyncio.all_tasks() - before)


def test_openapi_exposes_only_new_optional_feature(app: FastAPI) -> None:
    schema = app.openapi()
    assert "post" in schema["paths"][PATH]
    properties = schema["components"]["schemas"]["ComposerPredictionResponse"]["properties"]
    assert set(properties) == {
        "schemaVersion",
        "sourceTurnId",
        "contextVersion",
        "suggestion",
        "warnings",
        "usage",
    }


def test_generated_suggestion_length_is_schema_validated() -> None:
    assert ComposerPredictionCompletion(suggestion="가" * 160).suggestion is not None
    with pytest.raises(ValidationError):
        ComposerPredictionCompletion(suggestion="가" * 161)


async def test_unknown_usage_remains_null(fake_llm: FakeLlm, settings: Settings) -> None:
    fake_llm.queue_completion(
        ComposerPredictionCompletion(suggestion=None),
        LlmUsage(model="test-model", input_tokens=None, output_tokens=None, reasoning_tokens=None),
    )
    result = await service(fake_llm, settings).execute(
        ComposerPredictionRequest.model_validate(payload())
    )
    assert result.usage is None


async def test_asgi_disconnect_after_body_parsing_stops_prediction(
    app: FastAPI,
    settings: Settings,
    fake_llm: FakeLlm,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings.edupilot_prediction_enabled = True
    started = asyncio.Event()
    closed = asyncio.Event()
    body_sent = False
    sent: list[Message] = []

    async def slow(**kwargs: Any) -> None:
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            closed.set()

    async def receive() -> Message:
        nonlocal body_sent
        if not body_sent:
            body_sent = True
            return {
                "type": "http.request",
                "body": json.dumps(payload()).encode(),
                "more_body": False,
            }
        await started.wait()
        return {"type": "http.disconnect"}

    async def send(message: Message) -> None:
        sent.append(message)

    monkeypatch.setattr(fake_llm, "complete_json", AsyncMock(side_effect=slow))
    scope: Scope = {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": PATH,
        "raw_path": PATH.encode(),
        "query_string": b"",
        "root_path": "",
        "headers": [
            (b"content-type", b"application/json"),
            (b"x-internal-token", b"contract-test-token"),
        ],
        "client": ("127.0.0.1", 12345),
        "server": ("test", 80),
    }
    before = asyncio.all_tasks()
    async with app.router.lifespan_context(app):
        log_stream = StringIO()
        runtime = LoggingRuntime(environment=RuntimeEnvironment.PROD, stream=log_stream)
        try:
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(app(scope, receive, send), timeout=1)
        finally:
            runtime.close()
    assert closed.is_set()
    assert sent == []  # Do not manufacture a success/error response after disconnect.
    assert not (asyncio.all_tasks() - before)
    records = [json.loads(line) for line in log_stream.getvalue().splitlines()]
    cancelled_log = next(
        item for item in records if item["message"] == "internal request cancelled"
    )
    assert cancelled_log["level"] == "INFO"
    assert cancelled_log["status"] == "CANCELLED"
    assert "httpStatus" not in cancelled_log
    assert all(item["level"] != "ERROR" for item in records)
