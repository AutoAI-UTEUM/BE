"""Strict contracts for evidence-bound report generation and query."""

from typing import Literal, Self

from pydantic import Field, model_validator

from edupilot_ai.models.base import ContractModel
from edupilot_ai.models.turn import Usage

MetricWindow = Literal["CUMULATIVE", "RECENT"]
EvidenceSourceType = Literal["QUIZ", "QA", "DIAGNOSIS", "REPAIR", "MEMORY", "EXAM", "SESSION"]
CriterionStatus = Literal["ASSESSED", "INSUFFICIENT_DATA"]
WarningType = Literal[
    "CONFLICTING_EVIDENCE",
    "SPARSE_EVIDENCE",
    "OUT_OF_SCOPE_INPUT",
]
RefusalReason = Literal["OUT_OF_SNAPSHOT", "NO_EVIDENCE", "POLICY_REFUSED"]


class ReportScope(ContractModel):
    label: str | None = None
    period_start: str | None = None
    period_end: str | None = None


class ReportMetric(ContractModel):
    key: str = Field(min_length=1)
    label: str = Field(min_length=1)
    value: str
    window: MetricWindow


class CriterionEligibility(ContractModel):
    criterion_key: str = Field(min_length=1)
    eligible: bool
    reason: str | None = None


class ReportDataQuality(ContractModel):
    policy_version: str = Field(min_length=1)
    available_sources: list[EvidenceSourceType]
    missing_sources: list[EvidenceSourceType]
    criterion_eligibility: list[CriterionEligibility]


class ReportCriterion(ContractModel):
    key: str = Field(min_length=1)
    name: str = Field(min_length=1)
    description: str
    rubric: str
    allowed_source_types: list[EvidenceSourceType]
    minimum_evidence: int = Field(ge=0)
    version: int = Field(ge=1)


class ReportEvidence(ContractModel):
    evidence_id: str = Field(min_length=1)
    source_type: EvidenceSourceType
    occurred_at: str = Field(min_length=1)
    label: str = Field(min_length=1)
    fact: str = Field(min_length=1)


class PreviousCriterionResult(ContractModel):
    criterion_key: str = Field(min_length=1)
    status: CriterionStatus
    score: int | None = Field(default=None, ge=0, le=100)


class PreviousReportSummary(ContractModel):
    version: int = Field(ge=1)
    criterion_results: list[PreviousCriterionResult]


class ReportGenerateRequest(ContractModel):
    schema_version: Literal["1.0"]
    report_id: str = Field(min_length=1)
    generation_id: str = Field(min_length=1)
    scope: ReportScope
    metrics: list[ReportMetric]
    data_quality: ReportDataQuality
    criteria: list[ReportCriterion] = Field(min_length=1, max_length=20)
    evidence: list[ReportEvidence] = Field(max_length=200)
    previous_report: PreviousReportSummary | None = None

    @model_validator(mode="after")
    def validate_request_references(self) -> Self:
        criterion_keys = [criterion.key for criterion in self.criteria]
        if len(criterion_keys) != len(set(criterion_keys)):
            raise ValueError("criteria keys must be unique")

        evidence_ids = [item.evidence_id for item in self.evidence]
        if len(evidence_ids) != len(set(evidence_ids)):
            raise ValueError("evidence IDs must be unique")

        expected_criteria = set(criterion_keys)
        if any(
            item.criterion_key not in expected_criteria
            for item in self.data_quality.criterion_eligibility
        ):
            raise ValueError("criterion eligibility references an unknown criterion")

        available = set(self.data_quality.available_sources)
        missing = set(self.data_quality.missing_sources)
        if available & missing:
            raise ValueError("available and missing sources must be disjoint")
        return self


class EvidencedStatement(ContractModel):
    content: str = Field(min_length=1)
    evidence_ids: list[str] = Field(min_length=1)


class ReportCriterionResult(ContractModel):
    criterion_key: str = Field(min_length=1)
    status: CriterionStatus
    score: int | None = Field(default=None, ge=0, le=100)
    narrative: str = Field(min_length=40)
    evidence_ids: list[str]


class ReportSummary(ContractModel):
    overview: str = Field(min_length=40)
    strengths: list[EvidencedStatement]
    improvements: list[EvidencedStatement]
    misconception_candidates: list[EvidencedStatement]
    recommended_actions: list[EvidencedStatement]


class ReportWarning(ContractModel):
    type: WarningType
    message: str = Field(min_length=1)
    evidence_ids: list[str]


class ReportGenerateOutput(ContractModel):
    criterion_results: list[ReportCriterionResult] = Field(min_length=1)
    summary: ReportSummary
    warnings: list[ReportWarning]


class ReportGenerateResponse(ReportGenerateOutput):
    schema_version: Literal["1.0"] = "1.0"
    report_id: str = Field(min_length=1)
    usage: Usage


class ReportQueryRequest(ContractModel):
    schema_version: Literal["1.0"]
    report_id: str = Field(min_length=1)
    version: int = Field(ge=1)
    question: str = Field(min_length=1)
    report_summary: ReportSummary
    criterion_results: list[ReportCriterionResult]
    evidence: list[ReportEvidence] = Field(max_length=200)


class ReportQueryOutput(ContractModel):
    answerable: bool
    answer: str = Field(min_length=1)
    evidence_ids: list[str]
    refusal_reason: RefusalReason | None = None

    @model_validator(mode="after")
    def validate_answerability(self) -> Self:
        if self.answerable:
            if self.refusal_reason is not None or not self.evidence_ids:
                raise ValueError("answerable output requires evidence and no refusal reason")
        elif self.refusal_reason is None or self.evidence_ids:
            raise ValueError("refused output requires a reason and no evidence")
        return self


class ReportQueryResponse(ReportQueryOutput):
    schema_version: Literal["1.0"] = "1.0"
    usage: Usage
