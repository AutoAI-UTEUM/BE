"""Internal AI turn endpoint."""

import json
import logging
from collections.abc import AsyncIterator
from time import perf_counter
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from edupilot_ai.api.deps import get_turn_service
from edupilot_ai.core.errors import InternalApiError
from edupilot_ai.core.logging import bind_log_context, reset_log_context
from edupilot_ai.models.turn import TurnRequest, TurnResponse
from edupilot_ai.orchestration.service import TurnService

router = APIRouter(prefix="/internal/ai")
logger = logging.getLogger(__name__)


def _turn_fields(turn: TurnRequest) -> dict[str, str | int]:
    return {
        "turnId": turn.turn_id,
        "sessionId": turn.session.session_id,
    }


async def _logged_turn_stream(
    service: TurnService,
    turn: TurnRequest,
) -> AsyncIterator[str]:
    started_at = perf_counter()
    tokens = bind_log_context(turn_id=turn.turn_id)
    fields = _turn_fields(turn)
    error_code: str | None = None
    try:
        async for chunk in service.stream_ndjson(turn):
            try:
                event = json.loads(chunk)
                if event.get("type") == "error":
                    error_code = str(event.get("code", "AI_INTERNAL_ERROR"))
            except json.JSONDecodeError, AttributeError:
                pass
            yield chunk
        logger.log(
            logging.WARNING if error_code is not None else logging.INFO,
            "turn stream failed" if error_code is not None else "turn stream completed",
            extra={
                **fields,
                "status": "FAILED" if error_code is not None else "SUCCESS",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "errorCode": error_code,
            },
        )
    except Exception:
        logger.error(
            "turn stream failed unexpectedly",
            extra={
                **fields,
                "status": "FAILED",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "errorCode": "AI_INTERNAL_ERROR",
            },
        )
        raise
    finally:
        reset_log_context(tokens)


@router.post("/turn", response_model=TurnResponse)
async def execute_turn(
    request: Request,
    turn: TurnRequest,
    service: Annotated[TurnService, Depends(get_turn_service)],
) -> TurnResponse | StreamingResponse:
    """Negotiate NDJSON streaming while preserving the JSON contract."""
    accepted = {
        item.partition(";")[0].strip().lower()
        for item in request.headers.get("Accept", "").split(",")
    }
    if "application/x-ndjson" in accepted:
        return StreamingResponse(
            _logged_turn_stream(service, turn),
            media_type="application/x-ndjson",
            headers={
                "Cache-Control": "no-cache, no-store",
                "X-Accel-Buffering": "no",
            },
        )

    started_at = perf_counter()
    tokens = bind_log_context(turn_id=turn.turn_id)
    fields = _turn_fields(turn)
    try:
        response = await service.execute(turn)
        logger.info(
            "turn completed",
            extra={
                **fields,
                "status": "SUCCESS",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
            },
        )
        return response
    except InternalApiError as error:
        logger.warning(
            "turn failed",
            extra={
                **fields,
                "status": "FAILED",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "errorCode": error.code,
            },
        )
        raise
    except Exception:
        logger.error(
            "turn failed unexpectedly",
            extra={
                **fields,
                "status": "FAILED",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "errorCode": "AI_INTERNAL_ERROR",
            },
        )
        raise
    finally:
        reset_log_context(tokens)
