package io.edupilot.quiz;

@FunctionalInterface
public interface QuizPostGradingHook {

	QuizPostGradingHookResult onGraded(QuizPostGradingContext context);
}
