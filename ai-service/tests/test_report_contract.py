"""Contract tests for draft #121 report generation and query APIs."""

import logging
from copy import deepcopy
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from edupilot_ai.core.errors import InternalErrorResponse
from edupilot_ai.models.report import (
    CriterionEligibility,
    EvidencedStatement,
    ReportGenerateOutput,
    ReportGenerateRequest,
    ReportQueryOutput,
    ReportQueryRequest,
    ReportSummary,
)
from edupilot_ai.reporting.validator import (
    ReportValidationError,
    validate_generate_output,
)
from edupilot_ai.settings import ReasoningEffort
from tests.fakes import FakeLlm


def generate_payload() -> dict[str, Any]:
    return {
        "schemaVersion": "1.0",
        "reportId": "report-1",
        "generationId": "generation-1",
        "scope": {
            "label": "누적 학습",
            "periodStart": None,
            "periodEnd": None,
        },
        "metrics": [
            {
                "key": "quizAccuracy",
                "label": "퀴즈 정답률",
                "value": "80",
                "window": "CUMULATIVE",
            }
        ],
        "dataQuality": {
            "policyVersion": "report-quality-v1",
            "availableSources": ["QUIZ", "QA"],
            "missingSources": ["EXAM"],
            "criterionEligibility": [
                {
                    "criterionKey": "concept-understanding",
                    "eligible": True,
                    "reason": None,
                }
            ],
        },
        "criteria": [
            {
                "key": "concept-understanding",
                "name": "개념 이해도",
                "description": "핵심 개념 이해",
                "rubric": "근거와 계산 지표를 함께 해석",
                "allowedSourceTypes": ["QUIZ", "QA"],
                "minimumEvidence": 1,
                "version": 1,
            }
        ],
        "evidence": [
            {
                "evidenceId": "ev-1",
                "sourceType": "QUIZ",
                "occurredAt": "2026-08-01T00:00:00Z",
                "label": "퀴즈 1",
                "fact": "정답률 80%",
            },
            {
                "evidenceId": "ev-2",
                "sourceType": "QA",
                "occurredAt": "2026-08-01T01:00:00Z",
                "label": "질문 1",
                "fact": "편차 정의를 구체적으로 질문함",
            },
        ],
        "previousReport": None,
    }


def report_request() -> ReportGenerateRequest:
    return ReportGenerateRequest.model_validate(generate_payload())


def report_summary(
    *,
    misconception_evidence: list[str] | None = None,
) -> ReportSummary:
    misconceptions = (
        []
        if misconception_evidence is None
        else [
            EvidencedStatement(
                content="편차를 평균 자체로 오해할 가능성이 있습니다.",
                evidence_ids=misconception_evidence,
            )
        ]
    )
    return ReportSummary(
        overview="현재 개념 이해가 안정적으로 관찰됩니다.",
        strengths=[EvidencedStatement(content="핵심 정의를 이해합니다.", evidence_ids=["ev-1"])],
        improvements=[
            EvidencedStatement(content="응용 설명을 보완할 수 있습니다.", evidence_ids=["ev-2"])
        ],
        misconception_candidates=misconceptions,
        recommended_actions=[
            EvidencedStatement(content="유사 문제를 한 번 더 풉니다.", evidence_ids=["ev-1"])
        ],
    )


def report_output(
    *,
    criterion_key: str = "concept-understanding",
    status: str = "ASSESSED",
    score: int | None = 80,
    evidence_ids: list[str] | None = None,
    misconception_evidence: list[str] | None = None,
) -> ReportGenerateOutput:
    return ReportGenerateOutput.model_validate(
        {
            "criterionResults": [
                {
                    "criterionKey": criterion_key,
                    "status": status,
                    "score": score,
                    "narrative": "퀴즈와 질문에서 개념 이해가 관찰됩니다.",
                    "evidenceIds": evidence_ids if evidence_ids is not None else ["ev-1"],
                }
            ],
            "summary": report_summary(
                misconception_evidence=misconception_evidence
            ).model_dump(mode="json", by_alias=True),
            "warnings": [],
        }
    )


def query_request() -> ReportQueryRequest:
    request = report_request()
    return ReportQueryRequest(
        schema_version="1.0",
        report_id=request.report_id,
        version=1,
        question="이 학생이 다음에 복습할 내용은 무엇인가요?",
        report_summary=report_summary(),
        criterion_results=report_output().criterion_results,
        evidence=request.evidence,
    )


async def test_report_generate_endpoint_returns_contract_response(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(report_output())

    response = await client.post(
        "/internal/ai/reports/generate",
        headers=auth_headers,
        json=generate_payload(),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["schemaVersion"] == "1.0"
    assert body["reportId"] == "report-1"
    assert body["criterionResults"][0]["criterionKey"] == "concept-understanding"
    assert body["usage"]["model"] == "grok-4.5"
    assert fake_llm.calls[0][1].reasoning_effort is ReasoningEffort.HIGH
    assert fake_llm.timeouts == [180]
    assert "없는 ID를 만들지 마라" in fake_llm.calls[0][0][0]["content"]
    assert "한국어로 작성한다" in fake_llm.calls[0][0][0]["content"]


@pytest.mark.parametrize(
    "case",
    [
        "extra",
        "unknown_enum",
        "empty_criteria",
        "duplicate_criterion",
        "duplicate_evidence",
        "unknown_eligibility",
    ],
)
async def test_report_generate_request_contract_rejects_invalid_input(
    case: str,
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    payload = generate_payload()
    if case == "extra":
        payload["unexpected"] = True
    elif case == "unknown_enum":
        metrics = payload["metrics"]
        assert isinstance(metrics, list)
        metrics[0]["window"] = "WEEKLY"
    elif case == "empty_criteria":
        payload["criteria"] = []
    elif case == "duplicate_criterion":
        criteria = payload["criteria"]
        assert isinstance(criteria, list)
        criteria.append(deepcopy(criteria[0]))
    elif case == "duplicate_evidence":
        evidence = payload["evidence"]
        assert isinstance(evidence, list)
        evidence.append(deepcopy(evidence[0]))
    elif case == "unknown_eligibility":
        data_quality = payload["dataQuality"]
        assert isinstance(data_quality, dict)
        eligibility = data_quality["criterionEligibility"]
        assert isinstance(eligibility, list)
        eligibility[0]["criterionKey"] = "unknown"

    response = await client.post(
        "/internal/ai/reports/generate",
        headers=auth_headers,
        json=payload,
    )

    assert response.status_code == 422
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "AI_REQUEST_INVALID"
    assert error.error.category == "SCHEMA"


def _request_with_minimum_evidence(count: int) -> ReportGenerateRequest:
    request = report_request()
    criterion = request.criteria[0].model_copy(update={"minimum_evidence": count})
    return request.model_copy(update={"criteria": [criterion]})


def _request_with_eligibility(eligible: bool) -> ReportGenerateRequest:
    request = report_request()
    data_quality = request.data_quality.model_copy(
        update={
            "criterion_eligibility": [
                CriterionEligibility(
                    criterion_key="concept-understanding",
                    eligible=eligible,
                )
            ]
        }
    )
    return request.model_copy(update={"data_quality": data_quality})


@pytest.mark.parametrize(
    ("expected_reason", "generation_request", "output"),
    [
        (
            "CRITERION_KEY_MISMATCH",
            report_request(),
            report_output(criterion_key="unknown"),
        ),
        (
            "UNKNOWN_EVIDENCE_ID",
            report_request(),
            report_output(evidence_ids=["invented"]),
        ),
        (
            "DUPLICATE_EVIDENCE_ID",
            report_request(),
            report_output(evidence_ids=["ev-1", "ev-1"]),
        ),
        (
            "SCORE_STATUS_CONFLICT",
            report_request(),
            report_output(score=None),
        ),
        (
            "INSUFFICIENT_EVIDENCE_COUNT",
            _request_with_minimum_evidence(2),
            report_output(evidence_ids=["ev-1"]),
        ),
        (
            "INELIGIBLE_CRITERION_ASSESSED",
            _request_with_eligibility(False),
            report_output(),
        ),
        (
            "MISCONCEPTION_SINGLE_EVIDENCE",
            report_request(),
            report_output(misconception_evidence=["ev-1"]),
        ),
    ],
)
def test_generate_validator_reason_codes(
    expected_reason: str,
    generation_request: ReportGenerateRequest,
    output: ReportGenerateOutput,
) -> None:
    with pytest.raises(ReportValidationError) as captured:
        validate_generate_output(generation_request, output)
    assert captured.value.reason == expected_reason


async def test_report_generate_regenerates_after_unknown_evidence(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        report_output(evidence_ids=["invented"]),
        report_output(),
    )

    response = await client.post(
        "/internal/ai/reports/generate",
        headers=auth_headers,
        json=generate_payload(),
    )

    assert response.status_code == 200
    assert len(fake_llm.calls) == 2
    retry_system = fake_llm.calls[1][0][0]["content"]
    assert "UNKNOWN_EVIDENCE_ID" in retry_system
    assert "정답률 80%" not in retry_system


async def test_report_generate_validation_failure_twice_returns_schema_error(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        report_output(evidence_ids=["invented"]),
        report_output(evidence_ids=["invented"]),
    )

    response = await client.post(
        "/internal/ai/reports/generate",
        headers=auth_headers,
        json=generate_payload(),
    )

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "AI_RESPONSE_INVALID"
    assert error.error.category == "SCHEMA"
    assert error.error.retryable is False
    assert len(fake_llm.calls) == 2


async def test_report_validation_log_contains_only_safe_metadata(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    caplog: pytest.LogCaptureFixture,
) -> None:
    fake_llm.queue(
        report_output(evidence_ids=["invented"]),
        report_output(evidence_ids=["invented"]),
    )
    service_logger = logging.getLogger("edupilot_ai.reporting.service")
    service_logger.addHandler(caplog.handler)
    try:
        response = await client.post(
            "/internal/ai/reports/generate",
            headers=auth_headers,
            json=generate_payload(),
        )
    finally:
        service_logger.removeHandler(caplog.handler)

    assert response.status_code == 502
    record = next(
        item
        for item in caplog.records
        if item.message == "report output validation failed"
    )
    assert record.__dict__["reportId"] == "report-1"
    assert record.__dict__["generationId"] == "generation-1"
    assert record.__dict__["criterionCount"] == 1
    assert record.__dict__["evidenceCount"] == 2
    assert record.__dict__["errorCode"] == "UNKNOWN_EVIDENCE_ID"
    assert "정답률 80%" not in caplog.text
    assert "편차 정의를 구체적으로 질문함" not in caplog.text


def test_report_query_refusal_requires_reason() -> None:
    with pytest.raises(ValidationError):
        ReportQueryOutput(
            answerable=False,
            answer="선택된 리포트 근거로 답할 수 없습니다.",
            evidence_ids=[],
            refusal_reason=None,
        )


async def test_report_query_endpoint_returns_evidence_bound_answer(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        ReportQueryOutput(
            answerable=True,
            answer="편차 정의를 적용하는 문제를 복습하는 것이 좋습니다.",
            evidence_ids=["ev-1"],
            refusal_reason=None,
        )
    )

    response = await client.post(
        "/internal/ai/reports/query",
        headers=auth_headers,
        json=query_request().model_dump(mode="json", by_alias=True),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["schemaVersion"] == "1.0"
    assert body["answerable"] is True
    assert body["evidenceIds"] == ["ev-1"]
    assert body["usage"]["model"] == "grok-4.5"


async def test_report_query_unknown_evidence_twice_returns_schema_error(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    invalid = ReportQueryOutput(
        answerable=True,
        answer="복습이 필요합니다.",
        evidence_ids=["invented"],
        refusal_reason=None,
    )
    fake_llm.queue(invalid, invalid)

    response = await client.post(
        "/internal/ai/reports/query",
        headers=auth_headers,
        json=query_request().model_dump(mode="json", by_alias=True),
    )

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "AI_RESPONSE_INVALID"
    assert len(fake_llm.calls) == 2
    assert fake_llm.calls[0][1].reasoning_effort is ReasoningEffort.MEDIUM
    assert fake_llm.timeouts == [60, 60]


async def test_report_endpoint_requires_internal_token(
    client: httpx.AsyncClient,
) -> None:
    response = await client.post(
        "/internal/ai/reports/generate",
        json=generate_payload(),
    )

    assert response.status_code == 401
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.category == "AUTH"
