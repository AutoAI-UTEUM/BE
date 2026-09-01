package io.edupilot.admin.infra.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminInfraCostResponse(
	boolean available,
	Boolean stale,
	String reason,
	String currency,
	MonthToDate monthToDate,
	List<DailyCost> daily,
	Instant updatedAt,
	String note
) {

	public static AdminInfraCostResponse unavailable(String reason) {
		return new AdminInfraCostResponse(
			false,
			null,
			reason,
			null,
			null,
			null,
			null,
			null
		);
	}

	public AdminInfraCostResponse asStale(String failureReason) {
		return new AdminInfraCostResponse(
			true,
			true,
			failureReason,
			currency,
			monthToDate,
			daily,
			updatedAt,
			note
		);
	}

	public record MonthToDate(
		BigDecimal total,
		List<ServiceCost> byService
	) {
	}

	public record ServiceCost(
		String service,
		BigDecimal amount
	) {
	}

	public record DailyCost(
		LocalDate date,
		BigDecimal total
	) {
	}
}
