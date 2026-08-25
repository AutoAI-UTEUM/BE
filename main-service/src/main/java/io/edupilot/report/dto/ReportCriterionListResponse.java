package io.edupilot.report.dto;

import java.util.List;

public record ReportCriterionListResponse(
	List<ReportCriterionResponse> items
) {
}
