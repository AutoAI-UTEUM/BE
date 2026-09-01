package io.edupilot.admin.infra.dto;

import java.time.Instant;

public record AdminAppMetricsResponse(
	boolean available,
	Jvm jvm,
	Http http,
	Db db,
	double uptimeSeconds,
	AiService aiService
) {

	public record Jvm(
		long heapUsedBytes,
		long heapMaxBytes,
		long heapCommittedBytes,
		long liveThreads,
		long gcCount
	) {
	}

	public record Http(
		long requestCount,
		long serverErrorCount,
		double averageResponseTimeMs
	) {
	}

	public record Db(
		long activeConnections,
		long idleConnections,
		long maxConnections
	) {
	}

	public record AiService(
		String status,
		Instant checkedAt
	) {
	}
}
