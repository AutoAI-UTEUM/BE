package io.edupilot.exam.dto;

import java.time.Instant;

public record ExamAttemptDraftSaveResponse(int version, Instant savedAt) {
}
