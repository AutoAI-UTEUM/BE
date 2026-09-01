package io.edupilot.admin.infra.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminInfraMetricsResponse(
	boolean available,
	Boolean stale,
	String reason,
	String env,
	String range,
	Instant from,
	Instant to,
	Integer periodSeconds,
	Series series,
	Latest latest
) {

	public static AdminInfraMetricsResponse unavailable(String reason) {
		return new AdminInfraMetricsResponse(
			false,
			null,
			reason,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	public AdminInfraMetricsResponse asStale(String failureReason) {
		return new AdminInfraMetricsResponse(
			true,
			true,
			failureReason,
			env,
			range,
			from,
			to,
			periodSeconds,
			series,
			latest
		);
	}

	public record Point(
		Instant t,
		double v
	) {
	}

	public record Series(
		List<Point> cpu,
		List<Point> netIn,
		List<Point> netOut,
		List<Point> mem,
		List<Point> disk,
		List<Point> status
	) {
	}

	public record Latest(
		Double cpu,
		Double mem,
		Double disk,
		Double status
	) {
	}
}
