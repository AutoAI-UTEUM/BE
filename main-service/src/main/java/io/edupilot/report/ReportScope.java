package io.edupilot.report;

public record ReportScope(
	ReportScopeType type,
	Integer weekNumber
) {
	public ReportScope {
		if (type == ReportScopeType.FULL && weekNumber != null) {
			throw new IllegalArgumentException("FULL scope must not include weekNumber");
		}
		if (type == ReportScopeType.WEEK && (weekNumber == null || weekNumber < 1)) {
			throw new IllegalArgumentException("WEEK scope requires a positive weekNumber");
		}
	}

	public static ReportScope full() {
		return new ReportScope(ReportScopeType.FULL, null);
	}

	public static ReportScope week(int weekNumber) {
		return new ReportScope(ReportScopeType.WEEK, weekNumber);
	}
}
