"""A valid snapshot ID still must match the criterion's permitted source types."""

import httpx
import pytest

from edupilot_ai.reporting.validator import ReportValidationError, validate_generate_output
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
