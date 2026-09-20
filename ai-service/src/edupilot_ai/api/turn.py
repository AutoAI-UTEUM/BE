"""Internal AI turn endpoint."""

import asyncio
import json
import logging
from collections.abc import AsyncGenerator, AsyncIterator
from time import perf_counter
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from starlette.types import Send

from edupilot_ai.api.deps import get_turn_service
from edupilot_ai.core.async_iterators import closing_async_iterator, shielded_aclose
from edupilot_ai.core.errors import InternalApiError
from edupilot_ai.core.logging import bind_log_context, reset_log_context
from edupilot_ai.core.observability import ContentTiming
from edupilot_ai.models.turn import TurnRequest, TurnResponse
from edupilot_ai.orchestration.service import TurnService

router = APIRouter(prefix="/internal/ai")
logger = logging.getLogger(__name__)


class _ClosingStreamingResponse(StreamingResponse):
    """Close the body iterator in the task that consumes it on disconnect."""

    async def stream_response(self, send: Send) -> None:
        try:
            await super().stream_response(send)
        finally:
            await shielded_aclose(self.body_iterator)


def _turn_fields(turn: TurnRequest, *, streaming: bool) -> dict[str, str | int | bool]:
    return {
        "turnId": turn.turn_id,
        "sessionId": turn.session.session_id,
        "eventType": turn.event.event_type.value,
        "streaming": streaming,
    }


async def _next_chunk_with_turn_context(
    stream: AsyncIterator[str],
    *,
    turn_id: str,
) -> str:
    tokens = bind_log_context(turn_id=turn_id)
    try:
        return await anext(stream)
    finally:
        reset_log_context(tokens)


async def _logged_turn_stream(
    service: TurnService,
    turn: TurnRequest,
) -> AsyncGenerator[str]:
    started_at = perf_counter()
    fields = _turn_fields(turn, streaming=True)
    timing = ContentTiming(started_at)
    readiness: dict[str, float] = {}
    error_code: str | None = None
    cancelled = False
    stream = service.stream_ndjson(turn)
    try:
        async with closing_async_iterator(stream):
            while True:
                try:
                    chunk = await _next_chunk_with_turn_context(
                        stream,
                        turn_id=turn.turn_id,
                    )
                except StopAsyncIteration:
                    break
                try:
                    event = json.loads(chunk)
                    if event.get("type") == "error":
                        error_code = str(event.get("code", "AI_INTERNAL_ERROR"))
                    elif event.get("type") == "completed":
                        readiness["resultReadyMs"] = round((perf_counter() - started_at) * 1000, 3)
                    elif event.get("type") == "content_delta":
                        text = event.get("text")
                        if isinstance(text, str) and timing.observe(text):
                            logger.info(
                                "turn first content available",
                                extra={**fields, **timing.fields(), "status": "STREAMING"},
                            )
                except json.JSONDecodeError, AttributeError:
                    pass
                yield chunk
        logger.log(
            logging.WARNING if error_code is not None else logging.INFO,
            "turn stream failed" if error_code is not None else "turn stream completed",
            extra={
                **fields,
                **timing.fields(),
                **readiness,
                "status": "FAILED" if error_code is not None else "SUCCESS",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "errorCode": error_code,
            },
        )
    except GeneratorExit, asyncio.CancelledError:
        cancelled = True
        raise
    except Exception:
        logger.error(
            "turn stream failed unexpectedly",
            extra={
                **fields,
                **timing.fields(),
                **readiness,
                "status": "FAILED",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "errorCode": "AI_INTERNAL_ERROR",
            },
        )
        raise
    finally:
        if cancelled:
            logger.info(
                "turn stream cancelled by client",
                extra={
                    **fields,
                    **timing.fields(),
                    **readiness,
                    "status": "CANCELLED",
                    "durationMs": round((perf_counter() - started_at) * 1000, 3),
                },
            )


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
        return _ClosingStreamingResponse(
            _logged_turn_stream(service, turn),
            media_type="application/x-ndjson",
            headers={
                "Cache-Control": "no-cache, no-store",
                "X-Accel-Buffering": "no",
            },
        )

    started_at = perf_counter()
    tokens = bind_log_context(turn_id=turn.turn_id)
    fields = _turn_fields(turn, streaming=False)
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
