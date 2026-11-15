package io.edupilot.quiz.dto;

import java.util.List;

public record SaveQuizDraftRequest(Integer version, List<QuizSubmitRequest.Answer> answers) {
}
