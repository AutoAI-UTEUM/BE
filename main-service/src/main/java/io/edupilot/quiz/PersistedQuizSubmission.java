package io.edupilot.quiz;

import io.edupilot.quiz.dto.QuizSubmitResponse;

record PersistedQuizSubmission(
	QuizSubmitResponse response,
	boolean currentPageQuiz
) {
}
