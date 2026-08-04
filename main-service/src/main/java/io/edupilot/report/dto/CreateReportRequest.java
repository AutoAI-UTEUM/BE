package io.edupilot.report.dto;

import io.edupilot.report.ReportScope;
import io.edupilot.report.ReportScopeType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateReportRequest(
	@NotBlank String requestId,
	@NotNull ReportScopeType scope,
	@Min(1) Integer weekNumber
) {
	@AssertTrue(message = "weekNumber must be present only for WEEK scope")
	public boolean isScopeValid() {
		return scope == null
			|| scope == ReportScopeType.FULL && weekNumber == null
			|| scope == ReportScopeType.WEEK && weekNumber != null;
	}

	public ReportScope toScope() {
		return new ReportScope(scope, weekNumber);
	}
}
