"""One persistent app/provider pool per revision; parent communicates via private pipes."""

import asyncio
import contextlib
import importlib.metadata
import json
import logging
import os
import re
import sys
import time
from copy import deepcopy
from pathlib import Path
from typing import Any
from uuid import uuid4

import httpx
from pydantic import SecretStr

from tests.benchmarks.common import (
    BenchmarkError,
    BudgetTransport,
    OfflineTransport,
    RequestBudget,
    digest,
)
from tests.benchmarks.report import inspect_events

METRIC_MESSAGES = {
    "turn planning finished",
    "planner attempt completed",
    "planner attempt failed",
    "turn policy verification finished",
    "xAI first content available",
    "xAI chat completion finished",
    "turn first content available",
    "turn stream completed",
    "turn stream failed",
    "turn stream cancelled by client",
}
METRIC_FIELDS = {
    "durationMs",
    "attemptDurationMs",
    "firstContentMs",
    "lastContentMs",
    "contentSpanMs",
    "resultReadyMs",
    "inputTokens",
    "outputTokens",
    "reasoningTokens",
    "cachedInputTokens",
    "costUsdTicks",
    "numServerSideToolsUsed",
    "providerUsageFinal",
    "providerStatusCode",
    "attempt",
    "plannerAttempts",
    "llmCallId",
    "responseModel",
    "requestedModel",
    "providerModel",
    "reasoningEffort",
    "maxOutputTokens",
    "inputTextChars",
    "fileAttached",
    "fileCount",
    "status",
    "errorCode",
    "failureKind",
    "exceptionType",
    "planSource",
    "streaming",
    "promptCacheLayout",
    "promptCacheRouting",
}
CONFIG_FIELDS = {
    "model_name",
    "agent_reasoning_effort",
    "agent_max_tokens",
    "agent_temperature",
    "orchestrator_reasoning_effort",
    "orchestrator_max_tokens",
    "explainer_reasoning_effort",
    "qa_reasoning_effort",
    "qa_max_tokens",
    "quiz_reasoning_effort",
    "quiz_max_tokens",
    "note_reasoning_effort",
    "repair_reasoning_effort",
    "turn_timeout_seconds",
    "turn_first_event_timeout_seconds",
    "edupilot_prompt_cache_layout_enabled",
    "edupilot_prompt_cache_routing_enabled",
}


class MetricsHandler(logging.Handler):
    def __init__(self) -> None:
        super().__init__()
        self.rows: list[dict[str, Any]] = []

    def emit(self, record: logging.LogRecord) -> None:
        message = record.getMessage()
        if message not in METRIC_MESSAGES:
            return
        row: dict[str, Any] = {"message": message}
        for field in METRIC_FIELDS:
            value = getattr(record, field, None)
            if type(value) in {bool, int, float} or (
                isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", value)
            ):
                row[field] = value
        self.rows.append(row)


async def execute_turn(
    client: httpx.AsyncClient,
    case: dict[str, Any],
    token: str,
    sink: MetricsHandler,
) -> dict[str, Any]:
    # Import only after the worker's target source directory has been selected.
    from edupilot_ai.models.turn import TurnResponse

    payload = deepcopy(case["payload"])
    payload["turnId"] = str(uuid4())
    sink.rows.clear()
    started = time.perf_counter()
    response = await client.post(
        "/internal/ai/turn",
        json=payload,
        headers={
            "X-Internal-Token": token,
            "X-Trace-Id": str(uuid4()),
            "Accept": "application/x-ndjson",
        },
    )
    elapsed = (time.perf_counter() - started) * 1000
    events = [json.loads(line) for line in response.text.splitlines() if line.strip()]
    model_valid = True
    for event in events:
        if event.get("type") == "completed":
            try:
                TurnResponse.model_validate(event["result"])
            except ValueError:
                model_valid = False
    summary = (
        inspect_events(events, case)
        if model_valid
        else {"contractPass": False, "errorCodes": ["INVALID_COMPLETED_RESPONSE"]}
    )
    summary.update(
        httpStatus=response.status_code,
        success=response.status_code == 200 and model_valid and summary["contractPass"],
        clientElapsedMs=round(elapsed, 3),
        inputHash=case["inputHash"],
    )
    return {"summary": summary, "events": events, "metrics": sink.rows.copy()}


def reply(value: dict[str, Any]) -> None:
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True)


async def serve(config: dict[str, Any]) -> None:
    source = Path(config["repository"]).resolve() / "ai-service" / "src"  # noqa: ASYNC240 — startup only
    sys.path.insert(0, str(source))
    import edupilot_ai
    from edupilot_ai.factory import Dependencies, _xai_http_limits, create_app
    from edupilot_ai.llm.files import XaiFileClient
    from edupilot_ai.llm.xai import XaiLlmBridge
    from edupilot_ai.models.turn import TurnRequest
    from edupilot_ai.orchestration.agents import detect_page_redirect
    from edupilot_ai.settings import RuntimeEnvironment, Settings

    if not Path(str(edupilot_ai.__file__)).resolve().is_relative_to(source):  # noqa: ASYNC240 — startup
        raise BenchmarkError("WRONG_REVISION_IMPORTED")
    controls = config.get("settings", {})
    if not isinstance(controls, dict) or not set(controls) <= CONFIG_FIELDS:
        raise BenchmarkError("UNSUPPORTED_SETTINGS_OVERRIDE")
    live = config.get("live") is True
    secrets: dict[str, Any] = (
        {}
        if live
        else {
            "xai_api_key": SecretStr("offline-not-a-key"),
            "edupilot_internal_token": SecretStr("offline-not-a-token"),
        }
    )
    settings = Settings(
        _env_file=config.get("envFile") if live else None,
        environment=RuntimeEnvironment.LOCAL,
        **controls,
        **secrets,
    )
    for case in config["cases"]:
        TurnRequest.model_validate(case["payload"])
        if digest(case["payload"]) != case["inputHash"]:
            raise BenchmarkError("FIXTURE_HASH_MISMATCH")
        if (
            case["id"].startswith("qa_")
            and detect_page_redirect(case["payload"]["event"]["payload"]["message"]) is not None
        ):
            raise BenchmarkError("QA_CASE_HITS_DETERMINISTIC_REDIRECT")
    profiles = {
        name: getattr(settings, name + "_llm_profile").model_dump(mode="json")
        for name in ("orchestrator", "explainer", "qa", "quiz", "note", "repair")
    }
    effective = {
        key: value
        for key, value in settings.model_dump(mode="json").items()
        if key in CONFIG_FIELDS
    }
    transport: httpx.AsyncBaseTransport = OfflineTransport()
    if live:
        transport = BudgetTransport(
            httpx.AsyncHTTPTransport(retries=0, limits=_xai_http_limits()),
            RequestBudget(Path(config["budgetPath"])),
        )
    async with httpx.AsyncClient(transport=transport, follow_redirects=False) as provider:
        bridge = XaiLlmBridge(
            client=provider,
            api_key=settings.xai_api_key,
            prompt_cache_layout_enabled=settings.edupilot_prompt_cache_layout_enabled,
            prompt_cache_routing_enabled=settings.edupilot_prompt_cache_routing_enabled,
        )
        files = XaiFileClient(
            client=provider,
            api_key=settings.xai_api_key,
            timeout_seconds=settings.edupilot_xai_file_upload_timeout_seconds,
        )
        app = create_app(settings, Dependencies(llm_bridge=bridge, file_client=files))
        sink = MetricsHandler()
        logger = logging.getLogger("edupilot_ai")
        logger.addHandler(sink)
        try:
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app),
                    base_url="http://local-benchmark",
                ) as client:
                    reply(
                        {
                            "ready": True,
                            "profiles": profiles,
                            "settings": effective,
                            "python": sys.version.split()[0],
                            "packages": {
                                name: importlib.metadata.version(name)
                                for name in (
                                    "httpx",
                                    "pydantic",
                                    "pydantic-settings",
                                    "fastapi",
                                    "pypdf",
                                    "uvicorn",
                                )
                            },
                        }
                    )
                    while line := sys.stdin.readline():
                        command = json.loads(line)
                        if command["command"] == "close":
                            break
                        if not live or command["command"] != "turn":
                            raise BenchmarkError("LIVE_MODE_NOT_ENABLED")
                        result = await execute_turn(
                            client,
                            command["case"],
                            settings.edupilot_internal_token.get_secret_value(),
                            sink,
                        )
                        reply(result)
        finally:
            logger.removeHandler(sink)


def main() -> None:
    # Do not forward provider/validation exceptions, headers, prompts or raw log text.
    with open(os.devnull, "w") as discarded, contextlib.redirect_stderr(discarded):
        try:
            config = json.loads(sys.stdin.readline())
            asyncio.run(serve(config))
        except Exception as exc:
            reply({"fatal": str(exc) if isinstance(exc, BenchmarkError) else type(exc).__name__})
            raise SystemExit(1) from None


if __name__ == "__main__":
    main()
