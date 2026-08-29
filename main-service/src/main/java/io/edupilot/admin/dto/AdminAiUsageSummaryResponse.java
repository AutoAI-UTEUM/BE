package io.edupilot.admin.dto;

import java.util.List;

public record AdminAiUsageSummaryResponse(
	List<AdminAiUsageDailyResponse> daily,
	List<AdminAiUsageFeatureResponse> features
) {
}
