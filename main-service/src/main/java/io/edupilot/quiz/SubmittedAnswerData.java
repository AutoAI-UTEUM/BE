package io.edupilot.quiz;

import java.util.List;

public record SubmittedAnswerData(
	String schemaVersion,
	List<SubmittedAnswer> answers
) {
}
