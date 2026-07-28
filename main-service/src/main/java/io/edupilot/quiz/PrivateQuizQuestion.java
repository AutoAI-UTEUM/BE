package io.edupilot.quiz;

import java.util.List;

public record PrivateQuizQuestion(
	String questionId,
	String correctOptionId,
	Boolean correctAnswer,
	String explanation,
	String referenceAnswer,
	List<String> acceptableKeywords,
	List<RubricCriterion> rubric,
	String modelAnswer
) {
}
