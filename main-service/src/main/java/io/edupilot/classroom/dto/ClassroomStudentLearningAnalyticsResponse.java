package io.edupilot.classroom.dto;

import java.time.Instant;
import java.util.List;

public record ClassroomStudentLearningAnalyticsResponse(
	List<ClassroomStudentMaterialAnalyticsResponse> materials,
	List<ClassroomStudentQuestionByPageResponse> questionsByPage,
	List<ClassroomStudentQuizAnalyticsResponse> quizzes,
	Instant lastUpdatedAt
) {
}
