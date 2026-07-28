"""End-to-end JSON and NDJSON turn services."""

import asyncio
import time
from collections.abc import AsyncGenerator, AsyncIterator
from http import HTTPStatus

from edupilot_ai.core.errors import ErrorCategory, InternalApiError
from edupilot_ai.llm.bridge import LlmBridgeError, LlmUsage
from edupilot_ai.models.stream import (
    CompletedStreamEvent,
    ContentDeltaStreamEvent,
    ErrorStreamEvent,
    HeartbeatStreamEvent,
    StatusStreamEvent,
    ThoughtSummaryStreamEvent,
    TurnStreamEvent,
)
from edupilot_ai.models.turn import EventType, TurnRequest, TurnResponse, Usage
from edupilot_ai.orchestration.context import ContextBuilder
from edupilot_ai.orchestration.dispatcher import (
    DispatchResult,
    DispatchStreamCompleted,
    DispatchTextDelta,
    ToolDispatcher,
)
from edupilot_ai.orchestration.orchestrator import Orchestrator
from edupilot_ai.orchestration.policy import PolicyVerifier, PolicyViolation
from edupilot_ai.orchestration.timing import MonotonicClock, TurnDeadline

HEARTBEAT_INTERVAL_SECONDS = 10.0


def _llm_error(error: LlmBridgeError) -> InternalApiError:
    status = {
        ErrorCategory.TIMEOUT: HTTPStatus.GATEWAY_TIMEOUT,
        ErrorCategory.SCHEMA: HTTPStatus.BAD_GATEWAY,
    }.get(error.category, HTTPStatus.SERVICE_UNAVAILABLE)
    code = {
        ErrorCategory.TIMEOUT: "AI_SERVICE_TIMEOUT",
        ErrorCategory.SCHEMA: "AI_RESPONSE_INVALID",
    }.get(error.category, "AI_SERVICE_UNAVAILABLE")
    return InternalApiError(
        status_code=status,
        code=code,
        category=error.category,
        message="The AI service could not complete the turn.",
        retryable=error.retryable,
    )


def _policy_error(message: str) -> InternalApiError:
    return InternalApiError(
        status_code=HTTPStatus.BAD_GATEWAY,
        code="AI_POLICY_REJECTED",
        category=ErrorCategory.POLICY,
        message=message,
        retryable=False,
    )


def _stream_error(error: InternalApiError) -> ErrorStreamEvent:
    return ErrorStreamEvent(
        code=error.code,
        category=error.category,
        message=error.safe_message,
        retryable=error.retryable,
    )


def _usage(usages: list[LlmUsage], default_model: str) -> Usage:
    reasoning_values = [
        item.reasoning_tokens for item in usages if item.reasoning_tokens is not None
    ]
    return Usage(
        model=usages[-1].model if usages else default_model,
        input_tokens=sum(item.input_tokens for item in usages),
        output_tokens=sum(item.output_tokens for item in usages),
        reasoning_tokens=sum(reasoning_values) if reasoning_values else None,
    )


async def _next_event(
    events: AsyncIterator[TurnStreamEvent],
) -> TurnStreamEvent:
    return await events.__anext__()


async def events_with_heartbeat(
    events: AsyncGenerator[TurnStreamEvent],
    *,
    first_event_timeout_seconds: float,
    heartbeat_interval_seconds: float,
) -> AsyncIterator[TurnStreamEvent]:
    """Enforce first-event latency and emit heartbeats while one event is pending."""
    pending: asyncio.Task[TurnStreamEvent] | None = None
    first_event = True
    try:
        while True:
            if pending is None:
                pending = asyncio.create_task(_next_event(events))
            timeout = (
                first_event_timeout_seconds
                if first_event
                else heartbeat_interval_seconds
            )
            done, _ = await asyncio.wait({pending}, timeout=timeout)
            if not done:
                if first_event:
                    pending.cancel()
                    await asyncio.gather(pending, return_exceptions=True)
                    pending = None
                    yield ErrorStreamEvent(
                        code="AI_SERVICE_TIMEOUT",
                        category=ErrorCategory.TIMEOUT,
                        message="The AI service did not start the turn in time.",
                        retryable=True,
                    )
                    return
                yield HeartbeatStreamEvent()
                continue
            try:
                event = pending.result()
            except StopAsyncIteration:
                return
            pending = None
            first_event = False
            yield event
            if isinstance(event, (CompletedStreamEvent, ErrorStreamEvent)):
                await events.aclose()
                return
    finally:
        if pending is not None and not pending.done():
            pending.cancel()
            await asyncio.gather(pending, return_exceptions=True)


class TurnService:
    def __init__(
        self,
        *,
        context_builder: ContextBuilder,
        orchestrator: Orchestrator,
        policy: PolicyVerifier,
        dispatcher: ToolDispatcher,
        model: str,
        turn_timeout_seconds: float,
        first_event_timeout_seconds: float,
        heartbeat_interval_seconds: float = HEARTBEAT_INTERVAL_SECONDS,
        clock: MonotonicClock = time.monotonic,
    ) -> None:
        self._context_builder = context_builder
        self._orchestrator = orchestrator
        self._policy = policy
        self._dispatcher = dispatcher
        self._model = model
        self._turn_timeout_seconds = turn_timeout_seconds
        self._first_event_timeout_seconds = first_event_timeout_seconds
        self._heartbeat_interval_seconds = heartbeat_interval_seconds
        self._clock = clock

    async def execute(self, turn: TurnRequest) -> TurnResponse:
        """Execute the backward-compatible structured JSON path."""
        deadline = self._deadline()
        context = self._context_builder.build(turn)
        try:
            planned = await self._orchestrator.create_plan(context, deadline)
            plan, adjustments = self._policy.verify(planned.plan, context)
            dispatched = await self._dispatcher.dispatch(
                plan,
                context,
                deadline,
                adjustments,
            )
        except LlmBridgeError as error:
            raise _llm_error(error) from error
        except PolicyViolation as error:
            raise _policy_error(
                "The generated Plan violated the turn policy."
            ) from error
        self._raise_dispatch_failure(dispatched)
        return self._response(
            turn=turn,
            turn_goal=plan.turn_goal,
            dispatched=dispatched,
            usages=[planned.usage, *dispatched.usages],
        )

    async def stream_ndjson(self, turn: TurnRequest) -> AsyncIterator[str]:
        """Serialize the standard stream as one JSON object per line."""
        events = events_with_heartbeat(
            self.stream_events(turn),
            first_event_timeout_seconds=self._first_event_timeout_seconds,
            heartbeat_interval_seconds=self._heartbeat_interval_seconds,
        )
        async for event in events:
            yield event.model_dump_json(by_alias=True) + "\n"

    async def stream_events(
        self,
        turn: TurnRequest,
    ) -> AsyncGenerator[TurnStreamEvent]:
        """Execute Explainer/QA with text streaming and emit one terminal event."""
        if turn.event.event_type not in {
            EventType.EXPLAIN_CURRENT_PAGE,
            EventType.USER_QUESTION,
        }:
            try:
                yield CompletedStreamEvent(result=await self.execute(turn))
            except InternalApiError as error:
                yield _stream_error(error)
            except Exception:
                yield ErrorStreamEvent(
                    code="AI_INTERNAL_ERROR",
                    category=ErrorCategory.INTERNAL,
                    message="The AI service could not complete the turn.",
                    retryable=False,
                )
            return

        deadline = self._deadline()
        emitted_content: list[str] = []
        try:
            context = self._context_builder.build(turn)
            yield StatusStreamEvent(stage="PLANNING")
            yield ThoughtSummaryStreamEvent(text="학습 계획을 세우는 중입니다")
            planned = await self._orchestrator.create_plan(context, deadline)
            plan, adjustments = self._policy.verify(planned.plan, context)

            if turn.event.event_type is EventType.EXPLAIN_CURRENT_PAGE:
                yield StatusStreamEvent(stage="EXPLAINING")
                yield ThoughtSummaryStreamEvent(
                    text=f"{context.session.current_page}페이지 설명을 작성하는 중입니다"
                )
            else:
                yield StatusStreamEvent(stage="ANSWERING")
                yield ThoughtSummaryStreamEvent(
                    text=f"{context.session.current_page}페이지 근거로 답변을 작성하는 중입니다"
                )

            dispatched: DispatchResult | None = None
            async for item in self._dispatcher.dispatch_stream(
                plan,
                context,
                adjustments,
                deadline,
            ):
                if isinstance(item, DispatchTextDelta):
                    emitted_content.append(item.text)
                    yield ContentDeltaStreamEvent(text=item.text)
                elif isinstance(item, DispatchStreamCompleted):
                    dispatched = item.result
            if dispatched is None:
                raise RuntimeError("dispatcher stream did not terminate")
            self._raise_dispatch_failure(dispatched)

            yield StatusStreamEvent(stage="FINALIZING")
            result = self._response(
                turn=turn,
                turn_goal=plan.turn_goal,
                dispatched=dispatched,
                usages=[planned.usage, *dispatched.usages],
            )
            if "".join(emitted_content) != "".join(
                message.content for message in result.messages
            ):
                raise RuntimeError("stream content invariant violated")
            yield CompletedStreamEvent(result=result)
        except LlmBridgeError as error:
            yield _stream_error(_llm_error(error))
        except PolicyViolation:
            yield _stream_error(
                _policy_error("The generated Plan violated the turn policy.")
            )
        except InternalApiError as error:
            yield _stream_error(error)
        except Exception:
            yield ErrorStreamEvent(
                code="AI_INTERNAL_ERROR",
                category=ErrorCategory.INTERNAL,
                message="The AI service could not complete the turn.",
                retryable=False,
            )

    def _deadline(self) -> TurnDeadline:
        return TurnDeadline.start(
            self._turn_timeout_seconds,
            clock=self._clock,
        )

    def _raise_dispatch_failure(self, dispatched: DispatchResult) -> None:
        if dispatched.failure is None:
            return
        if isinstance(dispatched.failure, LlmBridgeError):
            raise _llm_error(dispatched.failure) from dispatched.failure
        raise _policy_error(
            "An agent result violated the turn policy."
        ) from dispatched.failure

    def _response(
        self,
        *,
        turn: TurnRequest,
        turn_goal: str,
        dispatched: DispatchResult,
        usages: list[LlmUsage],
    ) -> TurnResponse:
        return TurnResponse(
            turn_id=turn.turn_id,
            turn_goal=turn_goal,
            actions_executed=dispatched.actions,
            messages=dispatched.messages,
            state_patch=dispatched.state_patch,
            ui_actions=dispatched.ui_actions,
            memory_candidates=dispatched.memory_candidates,
            quiz=dispatched.quiz,
            usage=_usage(usages, self._model),
        )
