package io.edupilot.exam;

import java.util.List;

import io.edupilot.quiz.RubricCriterion;

public record ExamPrivateAnswer(
	String correctAnswer,
	String explanation,
	String modelAnswer,
	List<RubricCriterion> rubric
) {
}
