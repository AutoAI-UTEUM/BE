"""Pure Plan policy validation."""

from edupilot_ai.models.plan import PlanAction, ToolName, TurnPlan
from edupilot_ai.models.turn import EventType, QaThreadMode
from edupilot_ai.orchestration.context import AgentContext

PIPELINE_TOOLS = {
    ToolName.GRADE_OPEN_RESPONSE,
    ToolName.ASSESS_QUIZ_RESULT,
    ToolName.DIAGNOSE_MISCONCEPTION,
}


class PolicyViolation(Exception):
    pass


def _require_keys(action: PlanAction, expected: set[str]) -> None:
    if set(action.args) != expected:
        raise PolicyViolation("tool args do not match policy")


class PolicyVerifier:
    def verify(self, plan: TurnPlan, context: AgentContext) -> TurnPlan:
        if len(plan.actions) > plan.pedagogy_policy.intervention_budget:
            raise PolicyViolation("intervention budget exceeded")
        for action in plan.actions:
            if action.tool in PIPELINE_TOOLS:
                raise PolicyViolation("pipeline-only tool rejected")
            self._verify_action(action, context)
        return plan

    def _verify_action(self, action: PlanAction, context: AgentContext) -> None:
        event = context.event_type
        if event is EventType.EXPLAIN_CURRENT_PAGE:
            if action.tool is not ToolName.EXPLAIN_PAGE:
                raise PolicyViolation("tool does not match event")
            _require_keys(action, {"page", "detailLevel"})
            if action.args["page"] != context.session.current_page:
                raise PolicyViolation("page mismatch")
            if action.args["detailLevel"] != context.event_payload.detail_level:
                raise PolicyViolation("detail level mismatch")
            return
        if event is EventType.USER_QUESTION:
            if action.tool is not ToolName.ANSWER_QUESTION:
                raise PolicyViolation("tool does not match event")
            _require_keys(action, {"qaThreadMode", "threadRef"})
            try:
                mode = QaThreadMode(str(action.args["qaThreadMode"]))
            except ValueError as error:
                raise PolicyViolation("invalid qaThreadMode") from error
            thread_ref = action.args["threadRef"]
            if mode is QaThreadMode.FOLLOW_UP:
                if context.qa_thread_digest is None:
                    raise PolicyViolation("FOLLOW_UP requires qaThreadDigest")
                existing = context.qa_thread_ref()
                if existing is not None and thread_ref != existing:
                    raise PolicyViolation("threadRef mismatch")
                if not isinstance(thread_ref, str) or not thread_ref:
                    raise PolicyViolation("FOLLOW_UP requires threadRef")
            elif thread_ref is not None:
                raise PolicyViolation("START_NEW cannot invent threadRef")
            return
        if event is EventType.QUIZ_TYPE_SELECTED:
            expected = ToolName(f"GENERATE_QUIZ_{context.event_payload.quiz_type}")
            if action.tool is not expected:
                raise PolicyViolation("quiz tool mismatch")
            _require_keys(action, {"quizType"})
            if action.args["quizType"] != context.event_payload.quiz_type:
                raise PolicyViolation("quiz type mismatch")
            return
        if action.tool is not ToolName.REPAIR_MISCONCEPTION:
            raise PolicyViolation("repair tool mismatch")
        _require_keys(action, {"diagnosisId"})
        if action.args["diagnosisId"] != context.event_payload.diagnosis_id:
            raise PolicyViolation("diagnosis mismatch")
