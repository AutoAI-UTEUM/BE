package io.edupilot.quiz;

public record SubmittedAnswer(
	String questionId,
	String answer
) {
}
