"""Internal service authentication and trace propagation."""

from collections.abc import Awaitable, Callable
from hmac import compare_digest
from http import HTTPStatus
from uuid import uuid4

from fastapi import Request, Response
from pydantic import SecretStr
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.types import ASGIApp

from edupilot_ai.core.errors import ErrorCategory, build_error_response

INTERNAL_TOKEN_HEADER = "X-Internal-Token"  # noqa: S105 - this is a header name
TRACE_ID_HEADER = "X-Trace-Id"


class InternalTokenMiddleware(BaseHTTPMiddleware):
    """Authenticate internal routes with the DEC-014 static service token."""

    def __init__(self, app: ASGIApp, *, expected_token: SecretStr) -> None:
        super().__init__(app)
        self._expected_token = expected_token

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        trace_id = request.headers.get(TRACE_ID_HEADER, "").strip() or uuid4().hex
        request.state.trace_id = trace_id

        if request.url.path.startswith("/internal/"):
            supplied_token = request.headers.get(INTERNAL_TOKEN_HEADER, "")
            expected_token = self._expected_token.get_secret_value()
            if not supplied_token or not compare_digest(supplied_token, expected_token):
                return build_error_response(
                    status_code=HTTPStatus.UNAUTHORIZED,
                    trace_id=trace_id,
                    code="AI_INTERNAL_AUTH_FAILED",
                    category=ErrorCategory.AUTH,
                    message="Internal service authentication failed.",
                    retryable=False,
                )

        response = await call_next(request)
        response.headers[TRACE_ID_HEADER] = trace_id
        return response
