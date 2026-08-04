package io.edupilot.classroom.dto;

import java.time.Instant;
import java.util.List;

public record ClassroomAnalyticsResponse(
	long learnerCount,
	int averageProgressRate,
	long aiQuestionCountLast7Days,
	long inactiveLearnerCountLast7Days,
	Instant lastUpdatedAt,
	List<ClassroomAnalyticsMaterialResponse> materials,
	List<ClassroomQuestionByPageResponse> questionsByPage
) {
}
