"""Shared isolated application fixtures."""

from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
import pytest_asyncio
from fastapi import FastAPI
from pydantic import SecretStr

from edupilot_ai.factory import Dependencies, create_app
from edupilot_ai.settings import Settings
from tests.fakes import FakeLlm, FakeXaiFileClient


@pytest.fixture
def settings() -> Settings:
    return Settings(
        _env_file=None,
        edupilot_internal_token=SecretStr("contract-test-token"),
        xai_api_key=SecretStr("xai-test-not-real"),
        model_name="grok-4.5",
    )


@pytest.fixture
def fake_llm() -> FakeLlm:
    return FakeLlm()


@pytest.fixture
def fake_file_client() -> FakeXaiFileClient:
    return FakeXaiFileClient()


@pytest.fixture
def app(
    settings: Settings,
    fake_llm: FakeLlm,
    fake_file_client: FakeXaiFileClient,
) -> Iterator[FastAPI]:
    yield create_app(
        settings=settings,
        dependencies=Dependencies(
            llm_bridge=fake_llm,
            file_client=fake_file_client,
        ),
    )


@pytest_asyncio.fixture
async def client(app: FastAPI) -> AsyncIterator[httpx.AsyncClient]:
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://test",
        ) as async_client:
            yield async_client


@pytest.fixture
def auth_headers() -> dict[str, str]:
    return {
        "X-Internal-Token": "contract-test-token",
        "X-Trace-Id": "contract-test-trace",
    }


@pytest.fixture
def turn_payload() -> dict[str, object]:
    return {
        "schemaVersion": "1.0",
        "turnId": "turn-123",
        "session": {
            "sessionId": 100,
            "userId": 1,
            "materialId": 10,
            "currentPage": 3,
            "pageStatus": "NOT_EXPLAINED",
        },
        "event": {
            "eventType": "USER_QUESTION",
            "payload": {"message": "편차가 뭔지 모르겠어"},
        },
        "context": {
            "currentPageText": "편차 설명",
            "previousPageText": None,
            "nextPageText": None,
            "recentMessages": [],
            "qaThreadDigest": None,
            "quizAssessments": [],
            "learnerMemoryDigest": None,
            "learnerLevel": None,
            "learnerConfidence": None,
            "pendingDiagnosis": None,
            "latestRepair": None,
            "memory": {"temporaryCandidates": []},
        },
    }
