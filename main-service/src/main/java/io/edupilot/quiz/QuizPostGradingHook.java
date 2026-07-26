package io.edupilot.quiz;

import java.util.List;

import io.edupilot.session.UiAction;

@FunctionalInterface
public interface QuizPostGradingHook {

	List<UiAction> onGraded(QuizPostGradingContext context);
}
