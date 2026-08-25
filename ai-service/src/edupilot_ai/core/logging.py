"""Safe JSON logging and request-scoped correlation context."""

import json
import logging
from contextvars import ContextVar, Token
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, TextIO

from edupilot_ai.settings import RuntimeEnvironment

SERVICE_NAME = "ai-service"

_trace_id: ContextVar[str] = ContextVar("edupilot_trace_id", default="-")
_turn_id: ContextVar[str | None] = ContextVar("edupilot_turn_id", default=None)
_action_id: ContextVar[str | None] = ContextVar("edupilot_action_id", default=None)

_OPTIONAL_FIELDS = (
    "requestId",
    "turnId",
    "actionId",
    "sessionId",
    "endpoint",
    "agent",
    "tool",
    "status",
    "durationMs",
    "errorCode",
    "model",
    "attempt",
    "failureKind",
    "exceptionType",
    "reportId",
    "generationId",
    "criterionCount",
    "evidenceCount",
    "fileId",
    "sizeBytes",
)


@dataclass(frozen=True, slots=True)
class LogContextTokens:
    """Tokens needed to restore the context after one request or action."""

    trace_id: Token[str] | None = None
    turn_id: Token[str | None] | None = None
    action_id: Token[str | None] | None = None


def bind_log_context(
    *,
    trace_id: str | None = None,
    turn_id: str | None = None,
    action_id: str | None = None,
) -> LogContextTokens:
    """Bind only supplied safe identifiers to the current async context."""
    return LogContextTokens(
        trace_id=_trace_id.set(trace_id) if trace_id is not None else None,
        turn_id=_turn_id.set(turn_id) if turn_id is not None else None,
        action_id=_action_id.set(action_id) if action_id is not None else None,
    )


def reset_log_context(tokens: LogContextTokens) -> None:
    """Restore a context previously returned by :func:`bind_log_context`."""
    if tokens.action_id is not None:
        _action_id.reset(tokens.action_id)
    if tokens.turn_id is not None:
        _turn_id.reset(tokens.turn_id)
    if tokens.trace_id is not None:
        _trace_id.reset(tokens.trace_id)


class JsonLogFormatter(logging.Formatter):
    """Emit the approved allowlisted fields as one JSON object per record."""

    def __init__(self, *, environment: RuntimeEnvironment) -> None:
        super().__init__()
        self._environment = environment

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, tz=UTC).isoformat(),
            "level": record.levelname,
            "service": SERVICE_NAME,
            "environment": self._environment.value,
            "traceId": getattr(record, "traceId", None) or _trace_id.get(),
            "message": record.getMessage(),
        }
        contextual = {
            "turnId": _turn_id.get(),
            "actionId": _action_id.get(),
        }
        for field in _OPTIONAL_FIELDS:
            value = getattr(record, field, None)
            if value is None:
                value = contextual.get(field)
            if value is not None:
                payload[field] = value
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


class LoggingRuntime:
    """App-owned logging configuration that is reversible on lifespan exit."""

    def __init__(
        self,
        *,
        environment: RuntimeEnvironment,
        stream: TextIO | None = None,
    ) -> None:
        self._logger = logging.getLogger("edupilot_ai")
        self._previous_level = self._logger.level
        self._previous_propagate = self._logger.propagate
        self._handler = logging.StreamHandler(stream)
        self._handler.setFormatter(JsonLogFormatter(environment=environment))
        self._logger.addHandler(self._handler)
        self._logger.setLevel(
            logging.DEBUG if environment is RuntimeEnvironment.LOCAL else logging.INFO
        )
        self._logger.propagate = False

    def close(self) -> None:
        """Remove only the handler owned by this app instance."""
        self._logger.removeHandler(self._handler)
        self._handler.close()
        self._logger.setLevel(self._previous_level)
        self._logger.propagate = self._previous_propagate
