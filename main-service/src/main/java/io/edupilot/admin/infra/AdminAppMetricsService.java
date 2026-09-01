package io.edupilot.admin.infra;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.edupilot.admin.infra.dto.AdminAppMetricsResponse;
import io.edupilot.admin.infra.dto.AdminAppMetricsResponse.AiService;
import io.edupilot.admin.infra.dto.AdminAppMetricsResponse.Db;
import io.edupilot.admin.infra.dto.AdminAppMetricsResponse.Http;
import io.edupilot.admin.infra.dto.AdminAppMetricsResponse.Jvm;
import io.edupilot.global.config.ReadinessService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class AdminAppMetricsService {

	private static final Logger log = LoggerFactory.getLogger(
		AdminAppMetricsService.class
	);

	private final MeterRegistry meterRegistry;
	private final ReadinessService readinessService;
	private final Clock clock;

	public AdminAppMetricsService(
		MeterRegistry meterRegistry,
		ReadinessService readinessService,
		Clock clock
	) {
		this.meterRegistry = meterRegistry;
		this.readinessService = readinessService;
		this.clock = clock;
	}

	public AdminAppMetricsResponse metrics() {
		Collection<Timer> httpTimers = meterRegistry.find("http.server.requests")
			.timers();
		long requestCount = httpTimers.stream().mapToLong(Timer::count).sum();
		long serverErrorCount = httpTimers.stream()
			.filter(this::isServerError)
			.mapToLong(Timer::count)
			.sum();
		double totalTimeMs = httpTimers.stream()
			.mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS))
			.sum();
		double averageResponseTimeMs = requestCount == 0
			? 0.0
			: totalTimeMs / requestCount;

		Jvm jvm = new Jvm(
			Math.round(heapGauge("jvm.memory.used")),
			Math.round(heapGauge("jvm.memory.max")),
			Math.round(heapGauge("jvm.memory.committed")),
			Math.round(gaugeSum("jvm.threads.live")),
			meterRegistry.find("jvm.gc.pause").timers().stream()
				.mapToLong(Timer::count)
				.sum()
		);
		Http http = new Http(
			requestCount,
			serverErrorCount,
			averageResponseTimeMs
		);
		Db db = new Db(
			Math.round(gaugeSum("hikaricp.connections.active")),
			Math.round(gaugeSum("hikaricp.connections.idle")),
			Math.round(gaugeSum("hikaricp.connections.max"))
		);
		return new AdminAppMetricsResponse(
			true,
			jvm,
			http,
			db,
			uptimeSeconds(),
			aiServiceStatus()
		);
	}

	private double heapGauge(String name) {
		return sanitize(meterRegistry.find(name)
			.tag("area", "heap")
			.gauges()
			.stream()
			.mapToDouble(Gauge::value)
			.sum());
	}

	private double gaugeSum(String name) {
		return sanitize(meterRegistry.find(name)
			.gauges()
			.stream()
			.mapToDouble(Gauge::value)
			.sum());
	}

	private double uptimeSeconds() {
		Gauge uptime = meterRegistry.find("process.uptime").gauge();
		if (uptime != null && Double.isFinite(uptime.value())) {
			return uptime.value();
		}
		return ManagementFactory.getRuntimeMXBean().getUptime() / 1_000.0;
	}

	private AiService aiServiceStatus() {
		Instant checkedAt = clock.instant();
		try {
			String status = readinessService.check()
				.checks()
				.aiService()
				.name();
			return new AiService(status, checkedAt);
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue(
					"exceptionType",
					exception.getClass().getSimpleName()
				)
				.log("Admin app readiness query failed");
			return new AiService("DOWN", checkedAt);
		}
	}

	private boolean isServerError(Timer timer) {
		String outcome = timer.getId().getTag("outcome");
		String status = timer.getId().getTag("status");
		return "SERVER_ERROR".equals(outcome)
			|| (status != null && status.startsWith("5"));
	}

	private double sanitize(double value) {
		return Double.isFinite(value) ? value : 0.0;
	}
}
