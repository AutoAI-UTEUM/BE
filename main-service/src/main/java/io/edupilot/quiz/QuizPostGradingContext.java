package io.edupilot.quiz;

import java.util.List;

import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.session.UiAction;

public record QuizPostGradingContext(
	Long submissionId,
	Long quizId,
	Long sessionId,
	Long userId,
	Long materialId,
	QuizType quizType,
	String schemaVersion,
	List<PublicQuizQuestion> publicQuestions,
	List<PrivateQuizQuestion> privateQuestions,
	List<SubmittedAnswer> answers,
	GradingResult gradingResult,
	boolean passed,
	GradeRequest.PageContext pageContext,
	List<UiAction> defaultUiActions
) {
	public QuizPostGradingContext {
		publicQuestions = List.copyOf(publicQuestions);
		privateQuestions = List.copyOf(privateQuestions);
		answers = List.copyOf(answers);
		defaultUiActions = List.copyOf(defaultUiActions);
	}
}
