"""Test doubles for provider-neutral dependencies."""

import asyncio
from collections.abc import AsyncIterator, Mapping, Sequence
from dataclasses import dataclass

from pydantic import BaseModel

from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmCompletion,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmTextStreamItem,
    LlmUsage,
    ModelT,
)
from edupilot_ai.settings import AgentLlmProfile


@dataclass(frozen=True, slots=True)
class FakeCompletion:
    output: BaseModel
    usage: LlmUsage


@dataclass(frozen=True, slots=True)
class FakeStreamPause:
    seconds: float


type FakeStreamItem = str | LlmBridgeError | FakeStreamPause


@dataclass(frozen=True, slots=True)
class FakeTextStream:
    items: tuple[FakeStreamItem, ...]
    usage: LlmUsage | None


type ScriptItem = BaseModel | LlmBridgeError | FakeCompletion | FakeTextStream


class FakeLlm:
    """Scripted LLM double that fails on any unplanned call."""

    def __init__(self, responses: Sequence[ScriptItem] = ()) -> None:
        self._responses = list(responses)
        self.calls: list[tuple[Sequence[Mapping[str, str]], AgentLlmProfile]] = []
        self.timeouts: list[float] = []
        self.stream_calls: list[
            tuple[Sequence[Mapping[str, str]], AgentLlmProfile, float]
        ] = []

    def queue(self, *responses: ScriptItem) -> None:
        self._responses.extend(responses)

    def queue_completion(self, output: BaseModel, usage: LlmUsage) -> None:
        self._responses.append(FakeCompletion(output=output, usage=usage))

    def queue_text_stream(
        self,
        *items: FakeStreamItem,
        usage: LlmUsage | None = None,
    ) -> None:
        self._responses.append(FakeTextStream(items=items, usage=usage))

    async def complete_json(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        response_model: type[ModelT],
        profile: AgentLlmProfile,
        timeout_seconds: float,
    ) -> LlmCompletion[ModelT]:
        self.calls.append((messages, profile))
        self.timeouts.append(timeout_seconds)
        if not self._responses:
            raise AssertionError("Unexpected LLM call")
        response = self._responses.pop(0)
        if isinstance(response, LlmBridgeError):
            raise response
        if isinstance(response, FakeTextStream):
            raise AssertionError("Text stream was used for a structured LLM call")
        if isinstance(response, FakeCompletion):
            output = response.output
            usage = response.usage
        else:
            output = response
            usage = LlmUsage(
                model=profile.model,
                input_tokens=0,
                output_tokens=0,
                reasoning_tokens=None,
            )
        if not isinstance(output, response_model):
            raise AssertionError(
                f"Scripted response {type(output).__name__} does not match "
                f"{response_model.__name__}"
            )
        return LlmCompletion(
            output=output,
            usage=usage,
        )

    async def complete_text_stream(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        profile: AgentLlmProfile,
        timeout_seconds: float,
    ) -> AsyncIterator[LlmTextStreamItem]:
        self.stream_calls.append((messages, profile, timeout_seconds))
        if not self._responses:
            raise AssertionError("Unexpected LLM stream call")
        response = self._responses.pop(0)
        if not isinstance(response, FakeTextStream):
            raise AssertionError(
                f"Scripted response {type(response).__name__} is not a text stream"
            )
        for item in response.items:
            if isinstance(item, LlmBridgeError):
                raise item
            if isinstance(item, FakeStreamPause):
                await asyncio.sleep(item.seconds)
            else:
                yield LlmTextDelta(text=item)
        yield LlmTextStreamCompleted(
            usage=response.usage
            or LlmUsage(
                model=profile.model,
                input_tokens=0,
                output_tokens=0,
                reasoning_tokens=None,
            )
        )
