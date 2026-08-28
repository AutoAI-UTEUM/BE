"""Deterministic validation for structured report LLM outputs."""

from collections.abc import Iterable

from edupilot_ai.models.report import (
    EvidencedStatement,
    ReportGenerateOutput,
    ReportGenerateRequest,
    ReportQueryOutput,
    ReportQueryRequest,
)

_SCORE_STATUS_CONFLICT = "SCORE_STATUS_CONFLICT"
_MISCONCEPTION_SINGLE_EVIDENCE = "MISCONCEPTION_SINGLE_EVIDENCE"


class ReportValidationError(ValueError):
    """Validation failure carrying only a safe machine reason code."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _validate_evidence_ids(
    groups: Iterable[list[str]],
    *,
    allowed_ids: set[str],
) -> None:
    for evidence_ids in groups:
        if len(evidence_ids) != len(set(evidence_ids)):
            raise ReportValidationError("DUPLICATE_EVIDENCE_ID")
        if not set(evidence_ids) <= allowed_ids:
            raise ReportValidationError("UNKNOWN_EVIDENCE_ID")


def _summary_statements(output: ReportGenerateOutput) -> list[EvidencedStatement]:
    summary = output.summary
    return [
        *summary.strengths,
        *summary.improvements,
        *summary.misconception_candidates,
        *summary.recommended_actions,
    ]


def normalize_generate_output(
    request: ReportGenerateRequest,
    output: ReportGenerateOutput,
) -> tuple[ReportGenerateOutput, dict[str, int]]:
    """Conservatively repair local invariant violations without another LLM call."""
    criteria = {criterion.key: criterion for criterion in request.criteria}
    normalized_results = []
    corrected_score_status_count = 0
    for result in output.criterion_results:
        if (result.status == "ASSESSED") == (result.score is not None):
            normalized_results.append(result)
            continue

        criterion = criteria.get(result.criterion_key)
        criterion_name = criterion.name if criterion is not None else "해당 평가 기준"
        normalized_results.append(
            result.model_copy(
                update={
                    "status": "INSUFFICIENT_DATA",
                    "score": None,
                    "narrative": (
                        f"현재 제공된 근거만으로는 {criterion_name} 기준의 점수를 "
                        "확정하기 어렵습니다. 관련 학습 기록이 더 쌓이면 다시 평가할 "
                        "수 있습니다."
                    ),
                }
            )
        )
        corrected_score_status_count += 1

    misconception_candidates = [
        statement
        for statement in output.summary.misconception_candidates
        if len(set(statement.evidence_ids)) >= 2
    ]
    dropped_misconception_count = len(output.summary.misconception_candidates) - len(
        misconception_candidates
    )

    corrections: dict[str, int] = {}
    if corrected_score_status_count:
        corrections[_SCORE_STATUS_CONFLICT] = corrected_score_status_count
    if dropped_misconception_count:
        corrections[_MISCONCEPTION_SINGLE_EVIDENCE] = dropped_misconception_count
    if not corrections:
        return output, corrections

    return (
        output.model_copy(
            update={
                "criterion_results": normalized_results,
                "summary": output.summary.model_copy(
                    update={"misconception_candidates": misconception_candidates}
                ),
            }
        ),
        corrections,
    )


def validate_generate_output(
    request: ReportGenerateRequest,
    output: ReportGenerateOutput,
) -> None:
    """Validate output references and assessment invariants against its request."""
    criteria = {criterion.key: criterion for criterion in request.criteria}
    result_keys = [result.criterion_key for result in output.criterion_results]
    if len(result_keys) != len(set(result_keys)) or set(result_keys) != set(criteria):
        raise ReportValidationError("CRITERION_KEY_MISMATCH")

    allowed_ids = {item.evidence_id for item in request.evidence}
    evidence_groups = [
        *(result.evidence_ids for result in output.criterion_results),
        *(item.evidence_ids for item in _summary_statements(output)),
        *(warning.evidence_ids for warning in output.warnings),
    ]
    _validate_evidence_ids(evidence_groups, allowed_ids=allowed_ids)

    eligibility = {
        item.criterion_key: item.eligible for item in request.data_quality.criterion_eligibility
    }
    for result in output.criterion_results:
        if (result.status == "ASSESSED") != (result.score is not None):
            raise ReportValidationError(_SCORE_STATUS_CONFLICT)
        if result.status == "ASSESSED" and not result.evidence_ids:
            raise ReportValidationError("ASSESSED_WITHOUT_EVIDENCE")
        if result.status == "ASSESSED":
            if len(result.evidence_ids) < criteria[result.criterion_key].minimum_evidence:
                raise ReportValidationError("INSUFFICIENT_EVIDENCE_COUNT")
            if eligibility.get(result.criterion_key) is False:
                raise ReportValidationError("INELIGIBLE_CRITERION_ASSESSED")

    if any(result.status == "ASSESSED" for result in output.criterion_results):
        if not output.summary.recommended_actions:
            raise ReportValidationError("EMPTY_RECOMMENDED_ACTIONS")

    for statement in output.summary.misconception_candidates:
        if len(set(statement.evidence_ids)) < 2:
            raise ReportValidationError(_MISCONCEPTION_SINGLE_EVIDENCE)


def validate_query_output(
    request: ReportQueryRequest,
    output: ReportQueryOutput,
) -> None:
    """Ensure an answer cites only evidence from the selected report snapshot."""
    if not output.answerable:
        return
    allowed_ids = {item.evidence_id for item in request.evidence}
    _validate_evidence_ids([output.evidence_ids], allowed_ids=allowed_ids)
