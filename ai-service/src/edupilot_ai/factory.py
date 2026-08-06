"""FastAPI application factory."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

import httpx
from fastapi import FastAPI

from edupilot_ai.api.exams import router as exams_router
from edupilot_ai.api.extract import router as extract_router
from edupilot_ai.api.grade import router as grade_router
from edupilot_ai.api.health import router as health_router
from edupilot_ai.api.learning_support import router as learning_support_router
from edupilot_ai.api.reports import router as reports_router
from edupilot_ai.api.turn import router as turn_router
from edupilot_ai.core.errors import register_exception_handlers
from edupilot_ai.core.logging import LoggingRuntime
from edupilot_ai.core.middleware import InternalTokenMiddleware
from edupilot_ai.llm.bridge import LlmBridge
from edupilot_ai.llm.xai import XaiLlmBridge
from edupilot_ai.settings import Settings


@dataclass(frozen=True, slots=True)
class Dependencies:
    """Optional external dependencies owned by one app instance."""

    llm_bridge: LlmBridge | None = None


def create_app(
    settings: Settings | None = None,
    dependencies: Dependencies | None = None,
) -> FastAPI:
    """Create an isolated application instance for runtime or tests."""
    resolved_settings = settings if settings is not None else Settings()
    resolved_dependencies = dependencies if dependencies is not None else Dependencies()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        logging_runtime = LoggingRuntime(environment=resolved_settings.environment)
        owned_http_client: httpx.AsyncClient | None = None
        bridge = resolved_dependencies.llm_bridge
        if bridge is None:
            owned_http_client = httpx.AsyncClient()
            bridge = XaiLlmBridge(
                client=owned_http_client,
                api_key=resolved_settings.xai_api_key,
            )
        app.state.settings = resolved_settings
        app.state.llm_bridge = bridge
        try:
            yield
        finally:
            if owned_http_client is not None:
                await owned_http_client.aclose()
            logging_runtime.close()
            del app.state.llm_bridge
            del app.state.settings

    app = FastAPI(
        title="EduPilot AI Service",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.add_middleware(
        InternalTokenMiddleware,
        expected_token=resolved_settings.edupilot_internal_token,
    )
    register_exception_handlers(app)
    app.include_router(health_router)
    app.include_router(exams_router)
    app.include_router(extract_router)
    app.include_router(grade_router)
    app.include_router(learning_support_router)
    app.include_router(reports_router)
    app.include_router(turn_router)
    return app
