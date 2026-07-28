package io.edupilot.quiz;

import java.util.List;

public record QuizPublicData(
	String schemaVersion,
	List<PublicQuizQuestion> questions
) {
}
