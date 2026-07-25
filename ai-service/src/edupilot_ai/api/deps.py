"""FastAPI dependency providers."""

from typing import cast

from fastapi import Request

from edupilot_ai.llm.bridge import LlmBridge
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
