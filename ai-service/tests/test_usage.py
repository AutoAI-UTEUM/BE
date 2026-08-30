"""Cross-endpoint contract tests for optional provider usage."""

import pytest
from pydantic import BaseModel

from edupilot_ai.llm.bridge import LlmUsage
from edupilot_ai.models.base import Usage
from edupilot_ai.models.captions import CaptionsResponse
from edupilot_ai.models.conversation_summary import ConversationSummaryResponse
from edupilot_ai.models.criteria import CriteriaSuggestResponse
from edupilot_ai.models.doc_chat import DocChatResponse
from edupilot_ai.models.exam_draft import ExamDraftResponse
from edupilot_ai.models.extract import ExtractResponse
from edupilot_ai.models.files import XaiFileUploadResponse
from edupilot_ai.models.grading import GradeResponse
from edupilot_ai.models.learning_support import AssessmentResponse, DiagnosisResponse
from edupilot_ai.models.outline import OutlineResponse
from edupilot_ai.models.report import ReportGenerateResponse, ReportQueryResponse
from edupilot_ai.models.turn import TurnResponse
from edupilot_ai.usage import combine_llm_usages, response_usage


@pytest.mark.parametrize(
    "response_model",
    [
        TurnResponse,
        GradeResponse,
        AssessmentResponse,
        DiagnosisResponse,
        ReportGenerateResponse,
        ReportQueryResponse,
        ExamDraftResponse,
        DocChatResponse,
        OutlineResponse,
        CaptionsResponse,
        CriteriaSuggestResponse,
        ConversationSummaryResponse,
        ExtractResponse,
        XaiFileUploadResponse,
    ],
)
def test_every_internal_json_response_exposes_optional_usage(
    response_model: type[BaseModel],
) -> None:
    field = response_model.model_fields["usage"]

    assert field.default is None
    assert not field.is_required()


def test_usage_serializes_with_existing_camel_case_wire_keys() -> None:
    usage = Usage(
        model="grok-4.5",
        input_tokens=1234,
        output_tokens=567,
        reasoning_tokens=89,
    )

    assert usage.model_dump(by_alias=True) == {
        "model": "grok-4.5",
        "inputTokens": 1234,
        "outputTokens": 567,
        "reasoningTokens": 89,
    }


def test_usage_aggregation_sums_all_provider_calls() -> None:
    combined = combine_llm_usages(
        [
            LlmUsage("grok-4.5", 100, 20, 3),
            LlmUsage("grok-4.5", 200, 30, 4),
        ]
    )

    assert combined == LlmUsage("grok-4.5", 300, 50, 7)


def test_usage_is_null_when_any_provider_call_cannot_be_safely_summed() -> None:
    usage = response_usage(
        [
            LlmUsage("grok-4.5", 100, 20, 3),
            LlmUsage("grok-4.5", None, None, None),
        ]
    )

    assert usage is None
