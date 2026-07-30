"""Pure Plan policy validation."""

from edupilot_ai.models.plan import PlanAction, ToolName, TurnPlan
from edupilot_ai.models.turn import Adjustment, EventType, QaThreadMode
from edupilot_ai.orchestration.context import AgentContext

PIPELINE_TOOLS = {
    ToolName.GRADE_OPEN_RESPONSE,
    ToolName.ASSESS_QUIZ_RESULT,
    ToolName.DIAGNOSE_MISCONCEPTION,
}
MEMORY_TOOLS = {
    ToolName.BUILD_MEMORY_CANDIDATE,
    ToolName.PROMOTE_MEMORY,
}
MEMORY_TYPES = {"STRENGTH", "WEAKNESS", "MISCONCEPTION", "PREFERENCE"}
_QA_THREAD_MODE_ALIASES = {
    "NEW": QaThreadMode.START_NEW.value,
    "START_NEW": QaThreadMode.START_NEW.value,
    "FOLLOWUP": QaThreadMode.FOLLOW_UP.value,
    "FOLLOW-UP": QaThreadMode.FOLLOW_UP.value,
    "FOLLOW_UP": QaThreadMode.FOLLOW_UP.value,
}


class PolicyViolation(Exception):
    pass


def _normalized_action(action: PlanAction, expected: set[str]) -> PlanAction:
    args = dict(action.args)
    if "page" in expected and "pageNumber" in args and "page" not in args:
        args["page"] = args.pop("pageNumber")
    if not expected.issubset(args):
        raise PolicyViolation("tool args do not match policy")
    return action.model_copy(
        update={"args": {key: args[key] for key in expected}},
        deep=True,
    )


def _adjustment(
    *,
    action_id: str,
    field: str,
    from_: object,
    to: object,
    reason: str,
) -> Adjustment:
    return Adjustment(
        field=field,
        from_=from_,
        to=to,
        reason=reason,
    ).bind_to_action(action_id)


class PolicyVerifier:
    def verify(
        self,
        plan: TurnPlan,
        context: AgentContext,
    ) -> tuple[TurnPlan, list[Adjustment]]:
        if sum(
            action.tool is ToolName.PROMOTE_MEMORY for action in plan.actions
        ) > 1:
            raise PolicyViolation("multiple memory promotions in one turn")
        if len(plan.actions) > plan.pedagogy_policy.intervention_budget:
            raise PolicyViolation("intervention budget exceeded")
        if all(action.tool in MEMORY_TOOLS for action in plan.actions):
            raise PolicyViolation("memory tools require a primary action")
        corrected_actions: list[PlanAction] = []
        adjustments: list[Adjustment] = []
        for action in plan.actions:
            if action.tool in PIPELINE_TOOLS:
                raise PolicyViolation("pipeline-only tool rejected")
            if action.tool in MEMORY_TOOLS:
                corrected = self._verify_memory_action(action, context)
                action_adjustments: list[Adjustment] = []
            else:
                corrected, action_adjustments = self._verify_action(action, context)
            corrected_actions.append(corrected)
            adjustments.extend(action_adjustments)
        return (
            plan.model_copy(update={"actions": corrected_actions}, deep=True),
            adjustments,
        )

    def _verify_action(
        self,
        action: PlanAction,
        context: AgentContext,
    ) -> tuple[PlanAction, list[Adjustment]]:
        event = context.event_type
        if event is EventType.EXPLAIN_CURRENT_PAGE:
            if action.tool is not ToolName.EXPLAIN_PAGE:
                raise PolicyViolation("tool does not match event")
            corrected = _normalized_action(action, {"page", "detailLevel"})
            adjustments: list[Adjustment] = []
            args = dict(corrected.args)
            if args["page"] != context.session.current_page:
                adjustments.append(
                    _adjustment(
                        action_id=action.action_id,
                        field="page",
                        from_=args["page"],
                        to=context.session.current_page,
                        reason="PAGE_MISMATCH_CORRECTED",
                    )
                )
                args["page"] = context.session.current_page
            detail_level = context.event_payload.detail_level
            if args["detailLevel"] != detail_level:
                adjustments.append(
                    _adjustment(
                        action_id=action.action_id,
                        field="detailLevel",
                        from_=args["detailLevel"],
                        to=detail_level,
                        reason="EVENT_PAYLOAD_MISMATCH_CORRECTED",
                    )
                )
                args["detailLevel"] = detail_level
            return corrected.model_copy(update={"args": args}), adjustments
        if event is EventType.USER_QUESTION:
            if action.tool is not ToolName.ANSWER_QUESTION:
                raise PolicyViolation("tool does not match event")
            corrected = _normalized_action(action, {"qaThreadMode", "threadRef"})
            args = dict(corrected.args)
            raw_mode = args["qaThreadMode"]
            if isinstance(raw_mode, str):
                args["qaThreadMode"] = _QA_THREAD_MODE_ALIASES.get(
                    raw_mode.upper(),
                    raw_mode,
                )
            if args["qaThreadMode"] == QaThreadMode.START_NEW.value:
                args["threadRef"] = None
            corrected = corrected.model_copy(update={"args": args}, deep=True)
            try:
                mode = QaThreadMode(str(corrected.args["qaThreadMode"]))
            except ValueError as error:
                raise PolicyViolation("invalid qaThreadMode") from error
            thread_ref = corrected.args["threadRef"]
            if mode is QaThreadMode.FOLLOW_UP:
                if context.qa_thread_digest is None:
                    raise PolicyViolation("FOLLOW_UP requires qaThreadDigest")
                existing = context.qa_thread_ref()
                if thread_ref != existing:
                    raise PolicyViolation("threadRef mismatch")
                if not isinstance(thread_ref, str) or not thread_ref:
                    raise PolicyViolation("FOLLOW_UP requires threadRef")
            return corrected, []
        if event is EventType.QUIZ_TYPE_SELECTED:
            expected = ToolName(f"GENERATE_QUIZ_{context.event_payload.quiz_type}")
            if action.tool is not expected:
                raise PolicyViolation("quiz tool mismatch")
            corrected = _normalized_action(action, {"quizType"})
            if corrected.args["quizType"] != context.event_payload.quiz_type:
                raise PolicyViolation("quiz type mismatch")
            return corrected, []
        if action.tool is not ToolName.REPAIR_MISCONCEPTION:
            raise PolicyViolation("repair tool mismatch")
        if context.pending_diagnosis is None:
            raise PolicyViolation("repair requires pendingDiagnosis")
        corrected = _normalized_action(action, {"diagnosisId"})
        if corrected.args["diagnosisId"] != context.event_payload.diagnosis_id:
            raise PolicyViolation("diagnosis mismatch")
        if isinstance(context.pending_diagnosis, dict):
            pending_id = context.pending_diagnosis.get("diagnosisId")
            if pending_id is not None and pending_id != context.event_payload.diagnosis_id:
                raise PolicyViolation("pending diagnosis mismatch")
        return corrected, []

    def _verify_memory_action(
        self,
        action: PlanAction,
        context: AgentContext,
    ) -> PlanAction:
        if action.tool is ToolName.PROMOTE_MEMORY:
            return self._verify_memory_promotion(action, context)

        corrected = _normalized_action(
            action,
            {"type", "content", "confidence", "evidence"},
        )
        memory_type = corrected.args["type"]
        content = corrected.args["content"]
        confidence = corrected.args["confidence"]
        evidence = corrected.args["evidence"]
        if memory_type not in MEMORY_TYPES:
            raise PolicyViolation("memory type is not allowed")
        if not isinstance(content, str) or not content.strip():
            raise PolicyViolation("memory content is invalid")
        if (
            not isinstance(confidence, (int, float))
            or isinstance(confidence, bool)
            or not 0 <= float(confidence) <= 1
        ):
            raise PolicyViolation("memory confidence is invalid")
        if (
            not isinstance(evidence, list)
            or not evidence
            or any(not isinstance(item, str) or not item.strip() for item in evidence)
        ):
            raise PolicyViolation("memory evidence is invalid")
        unique_evidence = set(evidence)
        if len(unique_evidence) != len(evidence):
            raise PolicyViolation("memory evidence must be unique")
        return corrected

    @staticmethod
    def _verify_memory_promotion(
        action: PlanAction,
        context: AgentContext,
    ) -> PlanAction:
        corrected = _normalized_action(action, {"candidateIds"})
        candidate_ids = corrected.args["candidateIds"]
        if (
            not isinstance(candidate_ids, list)
            or not candidate_ids
            or any(
                not isinstance(candidate_id, int)
                or isinstance(candidate_id, bool)
                or candidate_id <= 0
                for candidate_id in candidate_ids
            )
        ):
            raise PolicyViolation("memory promotion candidateIds are invalid")
        if len(set(candidate_ids)) != len(candidate_ids):
            raise PolicyViolation("memory promotion candidateIds must be unique")

        available = {
            candidate.candidate_id: candidate
            for candidate in context.memory.temporary_candidates
        }
        if any(candidate_id not in available for candidate_id in candidate_ids):
            raise PolicyViolation("memory promotion candidateId is not in snapshot")

        selected = [available[candidate_id] for candidate_id in candidate_ids]
        if any(candidate.confidence < 0.7 for candidate in selected):
            raise PolicyViolation("memory promotion confidence threshold not met")
        evidence = {
            reference.identity()
            for candidate in selected
            for reference in candidate.evidence_refs
        }
        if len(evidence) < 2:
            raise PolicyViolation("memory promotion evidence threshold not met")
        return corrected
