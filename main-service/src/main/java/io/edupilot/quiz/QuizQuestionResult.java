package io.edupilot.quiz;

import java.util.List;

public record QuizQuestionResult(
	String questionId,
	String questionText,
	QuizType quizType,
	List<QuizOption> choices,
	String correctAnswer,
	String submittedAnswer
) {
}
