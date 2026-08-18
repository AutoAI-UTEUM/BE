"""Deterministic Plan synthesis for events with a single valid action."""

from edupilot_ai.models.plan import PedagogyPolicy, PlanAction, ToolName, TurnPlan
from edupilot_ai.models.turn import EventType, QaThreadMode
from edupilot_ai.orchestration.agents import detect_page_redirect
from edupilot_ai.orchestration.context import AgentContext


def _single_action_plan(
    *,
    turn_goal: str,
    tool: ToolName,
    args: dict[str, object],
) -> TurnPlan:
    return TurnPlan(
        turn_goal=turn_goal,
        pedagogy_policy=PedagogyPolicy(
            mode="DETERMINISTIC",
            reason="The event determines exactly one valid action.",
            allow_direct_answer=True,
            hint_depth="NONE",
            intervention_budget=1,
        ),
        actions=[PlanAction(action_id="action-1", tool=tool, args=args)],
        reason="The Plan was synthesized from the event contract.",
    )


def synthesize_plan(context: AgentContext) -> TurnPlan | None:
    """Synthesize fixed-outcome Plans; return None when an LLM must plan."""
    if context.event_type is EventType.EXPLAIN_CURRENT_PAGE:
        detail_level = context.event_payload.detail_level
        return _single_action_plan(
            turn_goal="EXPLAIN_CURRENT_PAGE",
            tool=ToolName.EXPLAIN_PAGE,
            args={
                "page": context.session.current_page,
                "detailLevel": detail_level.value if detail_level is not None else None,
            },
        )

    if context.event_type is EventType.QUIZ_TYPE_SELECTED:
        quiz_type = context.event_payload.quiz_type
        if quiz_type is None:
            return None
        return _single_action_plan(
            turn_goal="GENERATE_QUIZ",
            tool=ToolName(f"GENERATE_QUIZ_{quiz_type.value}"),
            args={"quizType": quiz_type.value},
        )

    if context.event_type is not EventType.USER_QUESTION:
        return None

    message = context.event_payload.message or ""
    fixed_guidance = detect_page_redirect(message) is not None or (
        context.page_attached and not (context.current_page_text or "").strip()
    )
    if not fixed_guidance:
        return None

    thread_ref = context.qa_thread_ref()
    mode = QaThreadMode.FOLLOW_UP if thread_ref is not None else QaThreadMode.START_NEW
    return _single_action_plan(
        turn_goal="ANSWER_USER_QUESTION",
        tool=ToolName.ANSWER_QUESTION,
        args={
            "qaThreadMode": mode.value,
            "threadRef": thread_ref,
        },
    )
