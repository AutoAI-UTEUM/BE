"""Health endpoint."""

from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict

router = APIRouter()


class HealthResponse(BaseModel):
    """Liveness response consumed by Spring."""

    model_config = ConfigDict(extra="forbid")

    status: Literal["UP"] = "UP"


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """Report process liveness without probing deferred external dependencies."""
    return HealthResponse()
