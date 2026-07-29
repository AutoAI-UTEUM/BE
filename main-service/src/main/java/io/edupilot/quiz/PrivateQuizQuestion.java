package io.edupilot.quiz;

import java.util.List;

public record PrivateQuizQuestion(
	String questionId,
	String answerChoiceId,
	Boolean answerValue,
	String explanation,
	String referenceAnswer,
	List<String> gradingCriteria,
	List<RubricCriterion> rubric,
	String modelAnswer
) {
}
