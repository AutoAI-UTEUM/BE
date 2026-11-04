"""Spring-only draft suggestions, independently cancellable from a turn."""

import asyncio
from typing import Annotated

from fastapi import APIRouter, Depends, Request

from edupilot_ai.api.deps import get_composer_prediction_service
from edupilot_ai.core.async_iterators import cancel_and_wait
from edupilot_ai.models.composer_prediction import (
    ComposerPredictionRequest,
    ComposerPredictionResponse,
)
from edupilot_ai.prediction.service import ComposerPredictionService

router = APIRouter(prefix="/internal/ai")


@router.post("/composer-prediction", response_model=ComposerPredictionResponse)
async def composer_prediction(
    payload: ComposerPredictionRequest,
    request: Request,
    service: Annotated[ComposerPredictionService, Depends(get_composer_prediction_service)],
) -> ComposerPredictionResponse:
    owner = asyncio.current_task()
    if owner is None:
        raise RuntimeError("prediction requires a running task")

    async def observe_disconnect() -> None:
        # FastAPI already consumed/validated the JSON body; we own receive now.
        while True:
            event = await request.receive()
            if event["type"] == "http.disconnect":
                owner.cancel()
                return

    monitor = asyncio.create_task(observe_disconnect())
    try:
        return await service.execute(payload)
    finally:
        await cancel_and_wait(monitor)
