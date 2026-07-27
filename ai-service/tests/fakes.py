"""Test doubles for provider-neutral dependencies."""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from pydantic import BaseModel

from edupilot_ai.llm.bridge import LlmBridgeError, LlmCompletion, LlmUsage, ModelT
from edupilot_ai.settings import AgentLlmProfile


@dataclass(frozen=True, slots=True)
class FakeCompletion:
    output: BaseModel
    usage: LlmUsage


ScriptItem = BaseModel | LlmBridgeError | FakeCompletion


class FakeLlm:
    """Scripted LLM double that fails on any unplanned call."""

    def __init__(self, responses: Sequence[ScriptItem] = ()) -> None:
        self._responses = list(responses)
        self.calls: list[tuple[Sequence[Mapping[str, str]], AgentLlmProfile]] = []

    def queue(self, *responses: ScriptItem) -> None:
        self._responses.extend(responses)

    def queue_completion(self, output: BaseModel, usage: LlmUsage) -> None:
        self._responses.append(FakeCompletion(output=output, usage=usage))

    async def complete_json(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        response_model: type[ModelT],
        profile: AgentLlmProfile,
    ) -> LlmCompletion[ModelT]:
        self.calls.append((messages, profile))
        if not self._responses:
            raise AssertionError("Unexpected LLM call")
        response = self._responses.pop(0)
        if isinstance(response, LlmBridgeError):
            raise response
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
