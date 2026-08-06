"""Deterministic validation for structured exam draft outputs."""

from collections import Counter

from edupilot_ai.models.exam_draft import ExamDraftOutput, ExamDraftRequest


class ExamDraftValidationError(ValueError):
    """Validation failure carrying only a safe machine reason code."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def validate_draft_output(
    request: ExamDraftRequest,
    output: ExamDraftOutput,
) -> None:
    """Validate question counts and references against the requested draft plan."""
    expected = Counter({item.question_type.value: item.count for item in request.question_plan})
    actual = Counter(question.question_type for question in output.questions)
    if actual != expected:
        raise ExamDraftValidationError("QUESTION_PLAN_MISMATCH")

    question_ids = [question.question_id for question in output.questions]
    if len(question_ids) != len(set(question_ids)):
        raise ExamDraftValidationError("DUPLICATE_QUESTION_ID")

    allowed_pages = {context.page_number for context in request.page_contexts}
    if any(
        question.source_page_number is not None and question.source_page_number not in allowed_pages
        for question in output.questions
    ):
        raise ExamDraftValidationError("UNKNOWN_SOURCE_PAGE")
