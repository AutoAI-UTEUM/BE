"""Content timing only; never retain or log the content being measured."""

from dataclasses import dataclass
from time import perf_counter


@dataclass(slots=True)
class ContentTiming:
    """Measure non-whitespace deltas against one local monotonic start time."""

    started_at: float
    first_at: float | None = None
    last_at: float | None = None

    def observe(self, text: str) -> bool:
        """Return true only for the first substantive delta (not status/heartbeat)."""
        if not text.strip():
            return False
        now = perf_counter()
        first = self.first_at is None
        if first:
            self.first_at = now
        self.last_at = now
        return first

    def fields(self) -> dict[str, float]:
        if self.first_at is None or self.last_at is None:
            return {}
        return {
            "firstContentMs": round((self.first_at - self.started_at) * 1000, 3),
            "lastContentMs": round((self.last_at - self.started_at) * 1000, 3),
            "contentSpanMs": round((self.last_at - self.first_at) * 1000, 3),
        }
