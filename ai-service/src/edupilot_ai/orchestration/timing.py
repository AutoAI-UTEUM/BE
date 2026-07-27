"""Turn-wide deadline used to distribute timeout budget across LLM calls."""

import time
from collections.abc import Callable
from dataclasses import dataclass

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import LlmBridgeError

type MonotonicClock = Callable[[], float]


@dataclass(frozen=True, slots=True)
class TurnDeadline:
    """A single deadline shared by Plan and all agent calls."""

    expires_at: float
    clock: MonotonicClock

    @classmethod
    def start(
        cls,
        timeout_seconds: float,
        *,
        clock: MonotonicClock = time.monotonic,
    ) -> TurnDeadline:
        return cls(
            expires_at=clock() + timeout_seconds,
            clock=clock,
        )

    def remaining_seconds(self) -> float:
        remaining = self.expires_at - self.clock()
        if remaining <= 0:
            raise LlmBridgeError(
                category=ErrorCategory.TIMEOUT,
                retryable=True,
            )
        return remaining
