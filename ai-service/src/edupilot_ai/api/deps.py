"""FastAPI dependency providers."""

from typing import Annotated, cast

from fastapi import Depends, Request

from edupilot_ai.llm.bridge import LlmBridge
from edupilot_ai.orchestration.agents import ExplainerAgent, QaAgent
from edupilot_ai.orchestration.context import ContextBuilder
from edupilot_ai.orchestration.dispatcher import ToolDispatcher
from edupilot_ai.orchestration.orchestrator import Orchestrator
from edupilot_ai.orchestration.policy import PolicyVerifier
from edupilot_ai.orchestration.service import TurnService
from edupilot_ai.settings import Settings


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


def get_turn_service(
    settings: Annotated[Settings, Depends(get_settings)],
    llm: Annotated[LlmBridge, Depends(get_llm_bridge)],
) -> TurnService:
    """Build one request-scoped turn pipeline from app-scoped dependencies."""
    explainer = ExplainerAgent(llm=llm, profile=settings.explainer_llm_profile)
    qa = QaAgent(llm=llm, profile=settings.qa_llm_profile)
    return TurnService(
        context_builder=ContextBuilder(),
        orchestrator=Orchestrator(llm=llm, profile=settings.orchestrator_llm_profile),
        policy=PolicyVerifier(),
        dispatcher=ToolDispatcher(explainer=explainer, qa=qa, model=settings.model_name),
        model=settings.model_name,
        turn_timeout_seconds=settings.turn_timeout_seconds,
        first_event_timeout_seconds=settings.turn_first_event_timeout_seconds,
    )
