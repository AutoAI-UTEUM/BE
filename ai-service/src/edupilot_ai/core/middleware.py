"""Pure ASGI internal authentication and trace propagation middleware."""

from hmac import compare_digest
from http import HTTPStatus
from uuid import uuid4

from pydantic import SecretStr
from starlette.datastructures import Headers, MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from edupilot_ai.core.errors import ErrorCategory, build_error_response

INTERNAL_TOKEN_HEADER = "X-Internal-Token"  # noqa: S105 - this is a header name
TRACE_ID_HEADER = "X-Trace-Id"


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

        path = str(scope.get("path", ""))
        if path.startswith("/internal/"):
            supplied_token = headers.get(INTERNAL_TOKEN_HEADER, "")
            expected_token = self._expected_token.get_secret_value()
            if not supplied_token or not compare_digest(supplied_token, expected_token):
                response = build_error_response(
                    status_code=HTTPStatus.UNAUTHORIZED,
                    trace_id=trace_id,
                    code="AI_INTERNAL_AUTH_FAILED",
                    category=ErrorCategory.AUTH,
                    message="Internal service authentication failed.",
                    retryable=False,
                )
                await response(scope, receive, send)
                return

        async def send_with_trace(message: Message) -> None:
            if message["type"] == "http.response.start":
                message = dict(message)
                message["headers"] = list(message.get("headers", []))
                response_headers = MutableHeaders(scope=message)
                response_headers[TRACE_ID_HEADER] = trace_id
            await send(message)

        await self._app(scope, receive, send_with_trace)
