package io.edupilot.quiz;

import java.util.List;

import io.edupilot.guardian.GuardianConsentFence;
import io.edupilot.session.UiAction;

/** Carries the AI stage's original consent generation to the final submission response check. */
public record QuizPostGradingHookResult(
	List<UiAction> uiActions,
	GuardianConsentFence.Snapshot guardianConsent
) {
	public QuizPostGradingHookResult {
		uiActions = List.copyOf(uiActions);
	}
}
