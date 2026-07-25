"""FastAPI application factory."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

from fastapi import FastAPI

from edupilot_ai.api.health import router as health_router
from edupilot_ai.api.turn import router as turn_router
from edupilot_ai.core.errors import register_exception_handlers
from edupilot_ai.core.middleware import InternalTokenMiddleware
from edupilot_ai.llm.bridge import LlmBridge
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
        app.state.settings = resolved_settings
        app.state.llm_bridge = resolved_dependencies.llm_bridge
        yield
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
    app.include_router(turn_router)
    return app
