"""Deterministic Plan synthesis for events with a single valid action."""

from edupilot_ai.models.plan import PedagogyPolicy, PlanAction, ToolName, TurnPlan
from edupilot_ai.models.turn import EventType, QaThreadMode
from edupilot_ai.orchestration.agents import detect_note_request, detect_page_redirect
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
    if (
        context.event_type is EventType.EXPLAIN_CURRENT_PAGE
        and context.page_quiz_decision is not None
        and (context.current_page_text or "").strip()
    ):
        # Preserve adaptive decisions even when the material has no file attachment.
        if (
            context.memory.temporary_candidates
            or context.quiz_assessments
            or context.pending_diagnosis is not None
            or context.latest_repair is not None
            or context.qa_thread_digest is not None
            or any(
                (message.get("senderType") or message.get("role")) == "USER"
                for message in context.recent_messages
            )
        ):
            return None
        plan = _single_action_plan(
            turn_goal="현재 페이지 설명",
            tool=ToolName.EXPLAIN_PAGE,
            args={
                "page": context.session.current_page,
                "detailLevel": context.event_payload.detail_level,
            },
        )
        if (
            context.page_quiz_decision.suggest_quiz
            and context.session.page_status == "NOT_EXPLAINED"
        ):
            plan.actions.append(
                PlanAction(
                    action_id="action-2",
                    tool=ToolName.PROMPT_BINARY_DECISION,
                    args={
                        "contentMarkdown": "퀴즈를 진행할까요?",
                        "decisionType": "QUIZ_DECISION",
                    },
                )
            )
            plan.pedagogy_policy.intervention_budget = 2
        plan.reason = "The current page has a material-level quiz decision supplied by Spring."
        return plan

    if context.event_type is EventType.EXPLAIN_CURRENT_PAGE and (
        context.attached_file_id is None or not (context.current_page_text or "").strip()
    ):
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

    if context.event_type is EventType.NOTE_REQUESTED:
        return _single_action_plan(
            turn_goal="WRITE_NOTE",
            tool=ToolName.WRITE_NOTE,
            args={"noteInstruction": "지금까지 학습한 내용을 복습용 노트로 정리하라."},
        )

    if context.event_type is not EventType.USER_QUESTION:
        return None

    message = context.event_payload.message or ""
    if detect_note_request(message):
        return _single_action_plan(
            turn_goal="WRITE_NOTE",
            tool=ToolName.WRITE_NOTE,
            args={"noteInstruction": message},
        )

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
