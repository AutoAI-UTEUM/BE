package io.edupilot.quiz.dto;

import java.time.Instant;
import java.util.List;

public record QuizDraftResponse(int version, List<QuizSubmitRequest.Answer> answers, Instant savedAt) {
}
