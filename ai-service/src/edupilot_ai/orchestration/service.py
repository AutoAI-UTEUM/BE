"""End-to-end non-streaming turn service."""

from http import HTTPStatus

from edupilot_ai.core.errors import ErrorCategory, InternalApiError
from edupilot_ai.llm.bridge import LlmBridgeError, LlmUsage
from edupilot_ai.models.turn import TurnRequest, TurnResponse, Usage
from edupilot_ai.orchestration.context import ContextBuilder
from edupilot_ai.orchestration.dispatcher import ToolDispatcher
from edupilot_ai.orchestration.orchestrator import Orchestrator
from edupilot_ai.orchestration.policy import PolicyVerifier, PolicyViolation


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


class TurnService:
    def __init__(
        self,
        *,
        context_builder: ContextBuilder,
        orchestrator: Orchestrator,
        policy: PolicyVerifier,
        dispatcher: ToolDispatcher,
        model: str,
    ) -> None:
        self._context_builder = context_builder
        self._orchestrator = orchestrator
        self._policy = policy
        self._dispatcher = dispatcher
        self._model = model

    async def execute(self, turn: TurnRequest) -> TurnResponse:
        context = self._context_builder.build(turn)
        try:
            planned = await self._orchestrator.create_plan(context)
            plan = self._policy.verify(planned.plan, context)
            dispatched = await self._dispatcher.dispatch(plan, context)
        except LlmBridgeError as error:
            raise _llm_error(error) from error
        except PolicyViolation as error:
            raise InternalApiError(
                status_code=HTTPStatus.BAD_GATEWAY,
                code="AI_POLICY_REJECTED",
                category=ErrorCategory.POLICY,
                message="The generated Plan violated the turn policy.",
                retryable=False,
            ) from error
        if dispatched.failure is not None:
            if isinstance(dispatched.failure, LlmBridgeError):
                raise _llm_error(dispatched.failure) from dispatched.failure
            raise InternalApiError(
                status_code=HTTPStatus.BAD_GATEWAY,
                code="AI_POLICY_REJECTED",
                category=ErrorCategory.POLICY,
                message="An agent result violated the turn policy.",
                retryable=False,
            )
        usages = [planned.usage, *dispatched.usages]
        return TurnResponse(
            turn_id=turn.turn_id,
            turn_goal=plan.turn_goal,
            actions_executed=dispatched.actions,
            messages=dispatched.messages,
            state_patch=dispatched.state_patch,
            ui_actions=dispatched.ui_actions,
            memory_candidates=[],
            usage=_usage(usages, self._model),
        )
