"""Grade v0.6 optional context and global validation envelope contract tests."""

import json
from copy import deepcopy

import httpx
import pytest

from tests.fakes import FakeLlm
from tests.test_quiz_grading import grade_payload, grader_output, post_grade


def assert_standard_422(
    response: httpx.Response,
    *,
    trace_id: str | None = None,
) -> None:
    assert response.status_code == 422
    body = response.json()
    assert "detail" not in body
    assert body["schemaVersion"] == "1.0"
    assert body["error"]["code"] == "AI_REQUEST_INVALID"
    assert body["error"]["category"] == "SCHEMA"
    assert body["error"]["retryable"] is False
    if trace_id is not None:
        assert body["traceId"] == trace_id


async def test_grade_optional_context_keys_may_be_omitted(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    payload = grade_payload()
    payload.pop("pageContext")
    payload.pop("learnerMemoryDigest")
    fake_llm.queue(grader_output())

    response = await post_grade(client, auth_headers, payload)

    assert response.status_code == 200


async def test_grade_optional_context_keys_may_be_explicit_null(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    payload = grade_payload()
    payload["pageContext"] = None
    payload["learnerMemoryDigest"] = None
    fake_llm.queue(grader_output())

    response = await post_grade(client, auth_headers, payload)

    assert response.status_code == 200


async def test_grade_with_page_context_remains_supported(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(grader_output())

    response = await post_grade(client, auth_headers, grade_payload())

    assert response.status_code == 200
    system_message = fake_llm.calls[0][0][0]["content"]
    assert "문제 의도, modelAnswer, rubric, 강의 자료 근거에 따라" in system_message
    assert "이 요청에는 강의 자료 문맥이 제공되지 않았다" not in system_message


async def test_grade_page_context_requires_text_when_present(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    payload = grade_payload()
    page_context = payload["pageContext"]
    assert isinstance(page_context, dict)
    page_context.pop("text")

    response = await post_grade(client, auth_headers, payload)

    assert_standard_422(response)


async def test_grade_without_page_context_omits_prompt_context_block(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    payload = grade_payload()
    payload.pop("pageContext")
    payload.pop("learnerMemoryDigest")
    fake_llm.queue(grader_output())

    response = await post_grade(client, auth_headers, payload)

    assert response.status_code == 200
    assert response.json()["score"] == 8
    assert response.json()["items"][0]["verdict"] == "CORRECT"
    messages = fake_llm.calls[0][0]
    prompt_payload = json.loads(messages[1]["content"])
    assert "pageContext" not in prompt_payload
    assert "learnerMemoryDigest" not in prompt_payload
    assert "items" in prompt_payload
    assert "studentAnswers" in prompt_payload
    assert "문제 의도, modelAnswer, rubric에 따라" in messages[0]["content"]
    assert "이 요청에는 강의 자료 문맥이 제공되지 않았다" in messages[0]["content"]


async def test_grade_positive_exam_id_is_echoed_as_integer(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    payload = grade_payload()
    payload["quizId"] = 987
    payload.pop("pageContext")
    payload.pop("learnerMemoryDigest")
    fake_llm.queue(grader_output())

    response = await post_grade(client, auth_headers, payload)

    assert response.status_code == 200
    assert response.json()["quizId"] == 987
    assert isinstance(response.json()["quizId"], int)


@pytest.mark.parametrize("quiz_id", ["exam-5", "123", 0, -1])
async def test_grade_rejects_non_positive_or_non_integer_quiz_id(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    quiz_id: object,
) -> None:
    payload = grade_payload()
    payload["quizId"] = quiz_id

    response = await post_grade(client, auth_headers, payload)

    assert_standard_422(response)


async def test_grade_missing_required_field_uses_standard_422_and_trace_id(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    payload = grade_payload()
    payload.pop("items")
    trace_id = "grade-v06-fixed-trace"
    headers = {**auth_headers, "X-Trace-Id": trace_id}

    response = await post_grade(client, headers, payload)

    assert_standard_422(response, trace_id=trace_id)


async def test_turn_missing_required_field_uses_same_standard_422(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload.pop("turnId")

    response = await client.post(
        "/internal/ai/turn",
        headers=auth_headers,
        json=payload,
    )

    assert_standard_422(response, trace_id="contract-test-trace")
