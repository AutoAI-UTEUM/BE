package io.edupilot.quiz.dto;

import java.util.List;

import io.edupilot.quiz.Quiz;
import io.edupilot.quiz.QuizType;

public record QuizDetailResponse(
	Long quizId,
	Long sessionId,
	QuizType quizType,
	String title,
	int page,
	int coverageStartPage,
	int coverageEndPage,
	int questionCount,
	List<QuizQuestionResponse> questions,
	boolean submitted
) {

	public static QuizDetailResponse from(Quiz quiz, boolean submitted) {
		List<QuizQuestionResponse> questions = quiz.getPublicQuestions()
			.stream()
			.map(QuizQuestionResponse::from)
			.toList();
		return new QuizDetailResponse(
			quiz.getId(),
			quiz.getSessionId(),
			quiz.getQuizType(),
			quiz.getTitle(),
			quiz.getPageNumber(),
			quiz.getCoverageStartPage(),
			quiz.getCoverageEndPage(),
			questions.size(),
			questions,
			submitted
		);
	}
}
