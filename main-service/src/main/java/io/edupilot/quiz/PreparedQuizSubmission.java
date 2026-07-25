package io.edupilot.quiz;

import java.util.List;

import io.edupilot.ai.dto.GradeRequest;

record PreparedQuizSubmission(
	Long quizId,
	Long sessionId,
	Long materialId,
	QuizType quizType,
	String schemaVersion,
	String requestId,
	List<PublicQuizQuestion> publicQuestions,
	List<PrivateQuizQuestion> privateQuestions,
	List<SubmittedAnswer> answers,
	GradeRequest.PageContext pageContext
) {
}
