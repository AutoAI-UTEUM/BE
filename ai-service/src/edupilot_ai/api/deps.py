"""FastAPI dependency providers."""

from typing import Annotated, cast

from fastapi import Depends, Request

from edupilot_ai.captions.service import CaptionService
from edupilot_ai.criteria.service import CriteriaSuggestService
from edupilot_ai.docchat.service import DocChatService
from edupilot_ai.examdraft.service import ExamDraftService
from edupilot_ai.grading.service import GraderAgent, GradeService
from edupilot_ai.llm.bridge import LlmBridge
from edupilot_ai.orchestration.agents import (
    ExplainerAgent,
    QaAgent,
    QuizAgent,
    RepairAgent,
)
from edupilot_ai.orchestration.context import ContextBuilder
from edupilot_ai.orchestration.dispatcher import ToolDispatcher
from edupilot_ai.orchestration.orchestrator import Orchestrator
from edupilot_ai.orchestration.policy import PolicyVerifier
from edupilot_ai.orchestration.service import TurnService
from edupilot_ai.outline.service import OutlineService
from edupilot_ai.reporting.service import ReportGenerationService, ReportQueryService
from edupilot_ai.settings import Settings
from edupilot_ai.support.service import QuizAssessmentService, QuizDiagnosisService


def get_settings(request: Request) -> Settings:
    """Return the settings owned by the current app instance."""
    return cast(Settings, request.app.state.settings)


def get_llm_bridge(request: Request) -> LlmBridge:
    """Return the app-scoped LLM bridge.

    The bootstrap has no production bridge implementation. A caller must inject
    one through ``Dependencies`` before an endpoint that needs an LLM can use it.
    """
    bridge = cast(LlmBridge | None, request.app.state.llm_bridge)
    if bridge is None:
        raise RuntimeError("LlmBridge is not configured")
    return bridge


def get_caption_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> CaptionService:
    return CaptionService(
        llm=llm,
        profile=settings.captions_llm_profile,
        timeout_seconds=settings.edupilot_captions_timeout_seconds,
    )


def get_turn_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> TurnService:
    """Build one request-scoped turn pipeline from app-scoped dependencies."""
    explainer = ExplainerAgent(llm=llm, profile=settings.explainer_llm_profile)
    qa = QaAgent(llm=llm, profile=settings.qa_llm_profile)
    quiz = QuizAgent(llm=llm, profile=settings.quiz_llm_profile)
    repair = RepairAgent(llm=llm, profile=settings.repair_llm_profile)
    return TurnService(
        context_builder=ContextBuilder(),
        orchestrator=Orchestrator(llm=llm, profile=settings.orchestrator_llm_profile),
        policy=PolicyVerifier(),
        dispatcher=ToolDispatcher(
            explainer=explainer,
            qa=qa,
            quiz=quiz,
            repair=repair,
            model=settings.model_name,
        ),
        model=settings.model_name,
        turn_timeout_seconds=settings.turn_timeout_seconds,
        first_event_timeout_seconds=settings.turn_first_event_timeout_seconds,
    )


def get_grade_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> GradeService:
    """Build one request-scoped deterministic grading pipeline."""
    return GradeService(
        agent=GraderAgent(
            llm=llm,
            profile=settings.grader_llm_profile,
            timeout_seconds=settings.grade_timeout_seconds,
        )
    )


def get_quiz_assessment_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> QuizAssessmentService:
    return QuizAssessmentService(
        llm=llm,
        profile=settings.assessment_llm_profile,
        timeout_seconds=settings.quiz_assessment_timeout_seconds,
    )


def get_quiz_diagnosis_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> QuizDiagnosisService:
    return QuizDiagnosisService(
        llm=llm,
        profile=settings.diagnosis_llm_profile,
        timeout_seconds=settings.diagnosis_timeout_seconds,
    )


def get_report_generation_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> ReportGenerationService:
    return ReportGenerationService(
        llm=llm,
        profile=settings.report_llm_profile,
        timeout_seconds=settings.report_timeout_seconds,
    )


def get_report_query_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> ReportQueryService:
    return ReportQueryService(
        llm=llm,
        profile=settings.report_query_llm_profile,
        timeout_seconds=settings.report_query_timeout_seconds,
    )


def get_exam_draft_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> ExamDraftService:
    return ExamDraftService(
        llm=llm,
        profile=settings.exam_draft_llm_profile,
        timeout_seconds=settings.exam_draft_timeout_seconds,
    )


def get_outline_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> OutlineService:
    return OutlineService(
        llm=llm,
        profile=settings.outline_llm_profile,
        timeout_seconds=settings.edupilot_outline_timeout_seconds,
        max_chars_per_page=settings.edupilot_outline_max_chars_per_page,
        min_chars_per_page=settings.edupilot_extract_min_chars_per_page,
        min_meaningful_page_ratio=settings.edupilot_extract_min_meaningful_page_ratio,
    )


def get_criteria_suggest_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> CriteriaSuggestService:
    return CriteriaSuggestService(
        llm=llm,
        profile=settings.criteria_llm_profile,
        timeout_seconds=settings.edupilot_criteria_timeout_seconds,
    )


def get_doc_chat_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> DocChatService:
    return DocChatService(
        llm=llm,
        profile=settings.docchat_llm_profile,
        timeout_seconds=settings.edupilot_docchat_timeout_seconds,
        max_context_chars=settings.edupilot_docchat_max_context_chars,
    )
