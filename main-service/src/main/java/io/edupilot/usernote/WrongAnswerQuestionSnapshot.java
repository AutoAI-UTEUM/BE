package io.edupilot.usernote;

import java.util.List;

import io.edupilot.quiz.QuizOption;
import io.edupilot.quiz.QuizType;

public record WrongAnswerQuestionSnapshot(
	String questionId,
	String questionText,
	QuizType quizType,
	List<QuizOption> choices,
	String correctAnswer,
	String submittedAnswer
) {
}
