package io.edupilot.exam;

import java.util.List;

import io.edupilot.quiz.QuizOption;

public record ExamPublicQuestion(
	String question,
	List<QuizOption> options
) {
}
