package io.edupilot.exam.dto;

import io.edupilot.quiz.QuizOption;

public record ExamOptionResponse(
	String optionId,
	String text
) {
	public static ExamOptionResponse from(QuizOption option) {
		return new ExamOptionResponse(option.choiceId(), option.text());
	}
}
