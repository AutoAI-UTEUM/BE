"""Standard internal API error contract."""

from enum import StrEnum
from http import HTTPStatus
from typing import Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict

SCHEMA_VERSION: Literal["1.0"] = "1.0"


class ErrorCategory(StrEnum):
    """Stable categories Spring maps to external errors."""

    AUTH = "AUTH"
    TIMEOUT = "TIMEOUT"
    SCHEMA = "SCHEMA"
    POLICY = "POLICY"
    INTERNAL = "INTERNAL"


class ErrorDetail(BaseModel):
    """Machine-readable error details safe for service boundaries."""

    model_config = ConfigDict(extra="forbid")

    code: str
    category: ErrorCategory
    message: str
    retryable: bool


class InternalErrorResponse(BaseModel):
    """Versioned internal API error envelope."""

    model_config = ConfigDict(extra="forbid")

    schemaVersion: Literal["1.0"] = SCHEMA_VERSION
    error: ErrorDetail
    traceId: str


def build_error_response(
    *,
    status_code: HTTPStatus,
    trace_id: str,
    code: str,
    category: ErrorCategory,
    message: str,
    retryable: bool,
) -> JSONResponse:
    """Serialize an error without exposing exception or prompt details."""
    envelope = InternalErrorResponse(
        error=ErrorDetail(
            code=code,
            category=category,
            message=message,
            retryable=retryable,
        ),
        traceId=trace_id,
    )
    return JSONResponse(
        status_code=status_code,
        content=envelope.model_dump(mode="json"),
        headers={"X-Trace-Id": trace_id},
    )


def request_trace_id(request: Request) -> str:
    """Read the trace ID assigned by middleware."""
    return str(request.state.trace_id)


async def validation_exception_handler(
    request: Request,
    _exception: Exception,
) -> JSONResponse:
    """Map Pydantic/FastAPI request validation failures to category SCHEMA."""
    return build_error_response(
        status_code=HTTPStatus.UNPROCESSABLE_ENTITY,
        trace_id=request_trace_id(request),
        code="AI_REQUEST_INVALID",
        category=ErrorCategory.SCHEMA,
        message="Request does not match the internal API schema.",
        retryable=False,
    )


async def unexpected_exception_handler(
    request: Request,
    _exception: Exception,
) -> JSONResponse:
    """Return a stable envelope for unhandled failures."""
    return build_error_response(
        status_code=HTTPStatus.INTERNAL_SERVER_ERROR,
        trace_id=request_trace_id(request),
        code="AI_INTERNAL_ERROR",
        category=ErrorCategory.INTERNAL,
        message="The AI service could not complete the request.",
        retryable=False,
    )


def register_exception_handlers(app: FastAPI) -> None:
    """Register app-scoped handlers without module-level app instances."""
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(Exception, unexpected_exception_handler)
