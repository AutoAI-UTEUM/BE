package io.edupilot.quiz;

@FunctionalInterface
public interface QuizPostGradingHook {

	void onGraded(QuizSubmissionSnapshot submission);
}
