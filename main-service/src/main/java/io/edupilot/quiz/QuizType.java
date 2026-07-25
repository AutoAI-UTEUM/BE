package io.edupilot.quiz;

public enum QuizType {
	MCQ,
	OX,
	SHORT,
	ESSAY;

	public boolean usesAiGrading() {
		return this == SHORT || this == ESSAY;
	}
}
