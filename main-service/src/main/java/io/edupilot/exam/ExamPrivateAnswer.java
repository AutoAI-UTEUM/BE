package io.edupilot.exam;

import java.util.List;

import io.edupilot.quiz.RubricCriterion;

public record ExamPrivateAnswer(
	String answerChoiceId,
	Boolean answerValue,
	String explanation,
	String referenceAnswer,
	String modelAnswer,
	List<RubricCriterion> rubric
) {
}
