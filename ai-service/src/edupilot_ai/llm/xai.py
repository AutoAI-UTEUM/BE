"""xAI OpenAI-compatible structured-output adapter."""

import json
import logging
from collections.abc import Mapping, Sequence
from typing import Any

import httpx
from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import LlmBridgeError, LlmCompletion, LlmUsage, ModelT
from edupilot_ai.settings import AgentLlmProfile

XAI_BASE_URL = "https://api.x.ai/v1"
XAI_CHAT_COMPLETIONS_URL = f"{XAI_BASE_URL}/chat/completions"

logger = logging.getLogger(__name__)


class _XaiModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class _CompletionTokenDetails(_XaiModel):
    reasoning_tokens: int | None = Field(default=None, ge=0)


class _CompletionUsage(_XaiModel):
    prompt_tokens: int = Field(ge=0)
    completion_tokens: int = Field(ge=0)
    completion_tokens_details: _CompletionTokenDetails | None = None


class _CompletionMessage(_XaiModel):
    content: str


class _CompletionChoice(_XaiModel):
    message: _CompletionMessage


class _CompletionResponse(_XaiModel):
    model: str
    choices: list[_CompletionChoice] = Field(min_length=1)
    usage: _CompletionUsage


class XaiLlmBridge:
    """Call xAI chat completions without embedding provider details in agents."""

    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        api_key: SecretStr,
        timeout_seconds: int,
    ) -> None:
        self._client = client
        self._api_key = api_key
        self._timeout = httpx.Timeout(float(timeout_seconds))

    async def complete_json(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        response_model: type[ModelT],
        profile: AgentLlmProfile,
    ) -> LlmCompletion[ModelT]:
        payload: dict[str, Any] = {
            "model": profile.model,
            "messages": [dict(message) for message in messages],
            "reasoning_effort": profile.reasoning_effort.value,
            "max_tokens": profile.max_tokens,
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": response_model.__name__,
                    "strict": True,
                    "schema": response_model.model_json_schema(by_alias=True),
                },
            },
        }
        if profile.temperature is not None:
            payload["temperature"] = profile.temperature

        try:
            response = await self._client.post(
                XAI_CHAT_COMPLETIONS_URL,
                headers={
                    "Authorization": f"Bearer {self._api_key.get_secret_value()}",
                    "Content-Type": "application/json",
                },
                json=payload,
                timeout=self._timeout,
            )
        except httpx.TimeoutException as exception:
            raise LlmBridgeError(
                category=ErrorCategory.TIMEOUT,
                retryable=True,
            ) from exception
        except httpx.RequestError as exception:
            raise LlmBridgeError(
                category=ErrorCategory.INTERNAL,
                retryable=True,
            ) from exception

        if response.is_error:
            raise LlmBridgeError(
                category=ErrorCategory.INTERNAL,
                retryable=response.status_code == 429 or response.status_code >= 500,
            )

        try:
            provider_response = _CompletionResponse.model_validate(response.json())
            content = provider_response.choices[0].message.content
            output = response_model.model_validate_json(content)
        except (json.JSONDecodeError, ValidationError, IndexError) as exception:
            raise LlmBridgeError(
                category=ErrorCategory.SCHEMA,
                retryable=False,
            ) from exception

        if provider_response.model != profile.model:
            logger.warning(
                "xAI response model mismatch: expected=%s actual=%s",
                profile.model,
                provider_response.model,
            )

        details = provider_response.usage.completion_tokens_details
        return LlmCompletion(
            output=output,
            usage=LlmUsage(
                model=provider_response.model,
                input_tokens=provider_response.usage.prompt_tokens,
                output_tokens=provider_response.usage.completion_tokens,
                reasoning_tokens=details.reasoning_tokens if details is not None else None,
            ),
        )
