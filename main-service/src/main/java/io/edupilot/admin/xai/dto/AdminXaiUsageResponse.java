package io.edupilot.admin.xai.dto;

import java.time.LocalDate;
import java.util.List;

public record AdminXaiUsageResponse(
	LocalDate from,
	LocalDate to,
	XaiUsageGranularity granularity,
	XaiUsageMetric metric,
	XaiUsageGroupBy groupBy,
	long unknownCostCalls,
	List<AdminXaiUsagePointResponse> items
) {
}
