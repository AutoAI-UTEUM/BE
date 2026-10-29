"""Provider identifiers stay out of file operation logs."""

import json
import logging

import httpx
import pytest
import respx
from pydantic import SecretStr

from edupilot_ai.core.logging import JsonLogFormatter
from edupilot_ai.llm.files import XAI_FILES_URL, XaiFileClient, XaiFileClientError
from edupilot_ai.settings import RuntimeEnvironment


@pytest.mark.parametrize("environment", list(RuntimeEnvironment))
def test_formatter_omits_provider_file_id(environment: RuntimeEnvironment) -> None:
    record = logging.LogRecord("edupilot_ai", logging.INFO, __file__, 1, "cleanup", (), None)
    record.fileId = "PRIVATE-PROVIDER-ID"
    record.tool = "files.delete"
    rendered = JsonLogFormatter(environment=environment).format(record)
    assert "PRIVATE-PROVIDER-ID" not in rendered
    assert "fileId" not in json.loads(rendered)
    assert json.loads(rendered)["tool"] == "files.delete"


@pytest.mark.parametrize("result", ["success", "missing", "rejected", "timeout", "network"])
async def test_delete_logs_no_provider_id(
    result: str,
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    private_id = "PRIVATE-PROVIDER-ID"
    private_body = "PRIVATE-PROVIDER-RESPONSE"
    route = respx_mock.delete(f"{XAI_FILES_URL}/{private_id}")
    if result == "timeout":
        route.mock(side_effect=httpx.ReadTimeout(private_body))
    elif result == "network":
        route.mock(side_effect=httpx.ConnectError(private_body))
    else:
        status = {"success": 204, "missing": 404, "rejected": 503}[result]
        route.mock(return_value=httpx.Response(status, text=private_body))
    async with httpx.AsyncClient() as http_client:
        client = XaiFileClient(
            client=http_client,
            api_key=SecretStr("PRIVATE-API-KEY"),
            timeout_seconds=1,
        )
        with caplog.at_level(logging.INFO, logger="edupilot_ai.llm.files"):
            if result in ("success", "missing"):
                await client.delete(private_id)
            else:
                with pytest.raises(XaiFileClientError) as error:
                    await client.delete(private_id)
                assert error.value.code == "FILE_DELETE_FAILED"
                assert error.value.retryable is True
    record = next(r for r in caplog.records if r.message == "xAI file delete finished")
    assert "fileId" not in record.__dict__
    assert record.__dict__["tool"] == "files.delete"
    assert record.__dict__["status"] == (
        "SUCCESS" if result in ("success", "missing") else "FAILED"
    )
    assert "durationMs" in record.__dict__
    for environment in RuntimeEnvironment:
        rendered = JsonLogFormatter(environment=environment).format(record)
        assert private_id not in rendered
        assert private_body not in rendered
        assert "PRIVATE-API-KEY" not in rendered
