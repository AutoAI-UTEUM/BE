package io.edupilot.quiz;

import java.util.List;

public record QuizPrivateData(
	String schemaVersion,
	List<PrivateQuizQuestion> questions
) {
}
