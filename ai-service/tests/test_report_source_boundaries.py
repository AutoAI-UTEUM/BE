"""A valid snapshot ID still must match the criterion's permitted source types."""

import json
import logging

import httpx
import pytest

from edupilot_ai.core.logging import JsonLogFormatter
from edupilot_ai.models.report import ReportGenerateRequest
from edupilot_ai.reporting.service import generate_messages
from edupilot_ai.reporting.validator import ReportValidationError, validate_generate_output
from edupilot_ai.settings import RuntimeEnvironment
from tests.fakes import FakeLlm
from tests.test_report_contract import generate_payload, report_output, report_request


@pytest.mark.parametrize("status", ["ASSESSED", "INSUFFICIENT_DATA"])
def test_criterion_rejects_known_but_disallowed_source(status: str) -> None:
    request = report_request()
    request.criteria[0].allowed_source_types = ["QUIZ"]
    output = report_output(
        status=status, score=80 if status == "ASSESSED" else None, evidence_ids=["ev-2"]
    )
    with pytest.raises(ReportValidationError, match="DISALLOWED_EVIDENCE_SOURCE"):
        validate_generate_output(request, output)


def test_mixed_sources_cannot_hide_disallowed_evidence() -> None:
    request = report_request()
    request.criteria[0].allowed_source_types = ["QUIZ"]
    with pytest.raises(ReportValidationError, match="DISALLOWED_EVIDENCE_SOURCE"):
        validate_generate_output(request, report_output(evidence_ids=["ev-1", "ev-2"]))


def test_allowed_source_keeps_existing_contract() -> None:
    request = report_request()
    request.criteria[0].allowed_source_types = ["QUIZ"]
    validate_generate_output(request, report_output(evidence_ids=["ev-1"]))


async def test_generation_retries_disallowed_source_with_safe_reason(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    payload = generate_payload()
    payload["criteria"][0]["allowedSourceTypes"] = ["QUIZ"]
    fake_llm.queue(report_output(evidence_ids=["ev-2"]), report_output(evidence_ids=["ev-1"]))
    response = await client.post(
        "/internal/ai/reports/generate", json=payload, headers=auth_headers
    )
    assert response.status_code == 200
    assert response.json()["criterionResults"][0]["evidenceIds"] == ["ev-1"]
    assert len(fake_llm.calls) == 2
    retry_message = fake_llm.calls[1][0][0]["content"]
    assert "DISALLOWED_EVIDENCE_SOURCE" in retry_message
    assert "편차 정의를 구체적으로 질문함" not in retry_message


async def test_repeated_disallowed_source_never_returns_completed_report(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    payload = generate_payload()
    payload["criteria"][0]["allowedSourceTypes"] = ["QUIZ"]
    fake_llm.queue(report_output(evidence_ids=["ev-2"]), report_output(evidence_ids=["ev-2"]))
    response = await client.post(
        "/internal/ai/reports/generate", json=payload, headers=auth_headers
    )
    assert response.status_code == 502
    assert "criterionResults" not in response.json()
    assert len(fake_llm.calls) == 2


def test_prompt_builds_per_criterion_allowlist_without_copying_facts_or_mutating_request() -> None:
    data = generate_payload()
    base = data["criteria"][0]
    data["criteria"] = [
        base | {"key": "quiz-only", "allowedSourceTypes": ["QUIZ"]},
        base | {"key": "qa-only", "allowedSourceTypes": ["QA"]},
        base | {"key": "both", "allowedSourceTypes": ["QA", "QUIZ"]},
        base | {"key": "no-match", "allowedSourceTypes": ["EXAM"]},
        base | {"key": "no-sources", "allowedSourceTypes": []},
    ]
    data["dataQuality"]["criterionEligibility"] = []
    request = ReportGenerateRequest.model_validate(data)
    before = request.model_dump()
    messages = generate_messages(request, retry=False)
    sent = json.loads(messages[1]["content"])
    assert [item.pop("allowedEvidenceIds") for item in sent["criteria"]] == [
        ["ev-1"],
        ["ev-2"],
        ["ev-1", "ev-2"],
        [],
        [],
    ]
    assert sent == request.model_dump(mode="json", by_alias=True)
    assert request.model_dump() == before
    assert messages == generate_messages(request, retry=False)
    assert "allowedEvidenceIds 안에서만 선택" in messages[0]["content"]
    assert "INSUFFICIENT_DATA에서도" in messages[0]["content"]
    assert "실제 rubric과 판단을 뒷받침하는 근거만" in messages[0]["content"]
    assert "다른 유형의 근거로 개수를 채우지 마라" in messages[0]["content"]
    assert "eligible=false" in messages[0]["content"]


def test_violation_identifies_request_index_not_output_position() -> None:
    request = report_request()
    request.criteria[0].allowed_source_types = ["QUIZ"]
    request.criteria.insert(0, request.criteria[0].model_copy(update={"key": "first"}))
    output = report_output(evidence_ids=["ev-2"])
    output.criterion_results.append(
        output.criterion_results[0].model_copy(
            update={"criterion_key": "first", "evidence_ids": ["ev-1"]}
        )
    )
    before = output.model_dump()
    with pytest.raises(ReportValidationError) as caught:
        validate_generate_output(request, output)
    assert caught.value.reason == "DISALLOWED_EVIDENCE_SOURCE"
    assert caught.value.source_violation is not None
    assert caught.value.source_violation.safe_details() == {
        "criterionIndex": 1,
        "allowedSourceTypes": ["QUIZ"],
        "disallowedSourceTypes": ["QA"],
        "disallowedEvidenceCount": 1,
    }
    assert output.model_dump() == before  # Never delete references to hide invalid reasoning.


@pytest.mark.parametrize("retry_succeeds", [True, False])
async def test_source_feedback_and_serialized_logs_are_safe_on_each_attempt(
    retry_succeeds: bool,
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    caplog: pytest.LogCaptureFixture,
) -> None:
    data = generate_payload()
    data["criteria"][0]["allowedSourceTypes"] = ["QUIZ"]
    fake_llm.queue(
        report_output(evidence_ids=["ev-2"]),
        report_output(evidence_ids=["ev-1"] if retry_succeeds else ["ev-2"]),
    )
    logger = logging.getLogger("edupilot_ai.reporting.service")
    previous_propagate = logger.propagate
    logger.propagate = False
    logger.addHandler(caplog.handler)
    try:
        response = await client.post(
            "/internal/ai/reports/generate", headers=auth_headers, json=data
        )
    finally:
        logger.removeHandler(caplog.handler)
        logger.propagate = previous_propagate
    assert response.status_code == (200 if retry_succeeds else 502)
    assert len(fake_llm.calls) == 2
    first = json.loads(str(fake_llm.calls[0][0][1]["content"]))
    second = json.loads(str(fake_llm.calls[1][0][1]["content"]))
    assert "validationFeedback" not in first
    details = {
        "criterionIndex": 0,
        "allowedSourceTypes": ["QUIZ"],
        "disallowedSourceTypes": ["QA"],
        "disallowedEvidenceCount": 1,
    }
    assert second.pop("validationFeedback") == details
    assert first == second
    records = [
        item for item in caplog.records if item.getMessage() == "report output validation failed"
    ]
    assert len(records) == (1 if retry_succeeds else 2)
    formatter = JsonLogFormatter(environment=RuntimeEnvironment.PROD)
    for attempt, record in enumerate(records, start=1):
        serialized = formatter.format(record)
        logged = json.loads(serialized)
        assert logged["attempt"] == attempt
        assert logged["errorCode"] == "DISALLOWED_EVIDENCE_SOURCE"
        assert {key: logged[key] for key in details} == details
        assert data["criteria"][0]["key"] not in serialized
        for evidence in data["evidence"]:
            for field in ("evidenceId", "label", "fact"):
                assert evidence[field] not in serialized


def test_unknown_evidence_is_still_rejected_before_source_diagnostics() -> None:
    with pytest.raises(ReportValidationError) as caught:
        validate_generate_output(report_request(), report_output(evidence_ids=["unknown"]))
    assert caught.value.reason == "UNKNOWN_EVIDENCE_ID"
    assert caught.value.source_violation is None
