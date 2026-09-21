package io.edupilot.admin.xai.dto;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

public record AdminXaiInvoicesResponse(
	YearMonth requestedPeriod,
	List<AdminXaiInvoiceResponse> items,
	Instant fetchedAt,
	Instant lastSuccessfulSyncAt,
	Boolean stale,
	boolean available
) {

	public static AdminXaiInvoicesResponse unavailable(YearMonth period) {
		return new AdminXaiInvoicesResponse(
			period,
			null,
			null,
			null,
			null,
			false
		);
	}
}
