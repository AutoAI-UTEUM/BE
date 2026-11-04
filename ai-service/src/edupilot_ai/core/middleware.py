"""Pure ASGI internal authentication and trace propagation middleware."""

import asyncio
import logging
from hmac import compare_digest
from http import HTTPStatus
from time import perf_counter
from uuid import uuid4

from pydantic import SecretStr
from starlette.datastructures import Headers, MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from edupilot_ai.core.errors import ErrorCategory, build_error_response
from edupilot_ai.core.logging import bind_log_context, reset_log_context

INTERNAL_TOKEN_HEADER = "X-Internal-Token"  # noqa: S105 - this is a header name
TRACE_ID_HEADER = "X-Trace-Id"
logger = logging.getLogger(__name__)


class InternalTokenMiddleware:
    """Authenticate internal routes without buffering streaming responses."""

    def __init__(self, app: ASGIApp, *, expected_token: SecretStr) -> None:
        self._app = app
        self._expected_token = expected_token

    async def __call__(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
    ) -> None:
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return

        headers = Headers(scope=scope)
        trace_id = headers.get(TRACE_ID_HEADER, "").strip() or uuid4().hex
        state = scope.setdefault("state", {})
        state["trace_id"] = trace_id
        started_at = perf_counter()
        status_code = HTTPStatus.INTERNAL_SERVER_ERROR
        response_started = False
        cancelled = False
        error_code: str | None = None
        tokens = bind_log_context(trace_id=trace_id)

        async def send_with_trace(message: Message) -> None:
            nonlocal status_code, response_started
            if message["type"] == "http.response.start":
                status_code = HTTPStatus(int(message["status"]))
                message = dict(message)
                message["headers"] = list(message.get("headers", []))
                response_headers = MutableHeaders(scope=message)
                response_headers[TRACE_ID_HEADER] = trace_id
            await send(message)
            if message["type"] == "http.response.start":
                response_started = True

        path = str(scope.get("path", ""))
        try:
            if path.startswith("/internal/"):
                supplied_token = headers.get(INTERNAL_TOKEN_HEADER, "")
                expected_token = self._expected_token.get_secret_value()
                if not supplied_token or not compare_digest(
                    supplied_token,
                    expected_token,
                ):
                    error_code = "AI_INTERNAL_AUTH_FAILED"
                    response = build_error_response(
                        status_code=HTTPStatus.UNAUTHORIZED,
                        trace_id=trace_id,
                        code=error_code,
                        category=ErrorCategory.AUTH,
                        message="Internal service authentication failed.",
                        retryable=False,
                    )
                    await response(scope, receive, send_with_trace)
                    return

            await self._app(scope, receive, send_with_trace)
        except asyncio.CancelledError:
            # Task cancellation is not an HTTP 500 and must not become an error envelope.
            cancelled = True
            raise
        except Exception:
            error_code = "AI_INTERNAL_ERROR"
            raise
        finally:
            level = (
                logging.INFO
                if cancelled
                else logging.ERROR
                if status_code >= HTTPStatus.INTERNAL_SERVER_ERROR
                else logging.WARNING
                if status_code >= HTTPStatus.BAD_REQUEST
                else logging.INFO
            )
            try:
                logger.log(
                    level,
                    "internal request cancelled" if cancelled else "internal request completed",
                    extra={
                        "traceId": trace_id,
                        "endpoint": path,
                        "status": "CANCELLED" if cancelled else int(status_code),
                        "httpStatus": int(status_code) if cancelled and response_started else None,
                        "durationMs": round((perf_counter() - started_at) * 1000, 3),
                        "errorCode": error_code,
                    },
                )
            finally:
                reset_log_context(tokens)
