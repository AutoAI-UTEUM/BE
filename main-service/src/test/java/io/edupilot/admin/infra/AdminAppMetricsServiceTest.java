package io.edupilot.admin.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.edupilot.admin.infra.dto.AdminAppMetricsResponse;
import io.edupilot.global.config.ReadinessResponse;
import io.edupilot.global.config.ReadinessService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AdminAppMetricsServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

	@Test
	void readsJvmHttpDbUptimeAndAiServiceMeters() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		gauge(registry, "jvm.memory.used", 100, "area", "heap");
		gauge(registry, "jvm.memory.max", 300, "area", "heap");
		gauge(registry, "jvm.memory.committed", 200, "area", "heap");
		gauge(registry, "jvm.threads.live", 12);
		gauge(registry, "hikaricp.connections.active", 2);
		gauge(registry, "hikaricp.connections.idle", 8);
		gauge(registry, "hikaricp.connections.max", 10);
		gauge(registry, "process.uptime", 123);

		Timer gc = Timer.builder("jvm.gc.pause").register(registry);
		gc.record(1, TimeUnit.MILLISECONDS);
		gc.record(2, TimeUnit.MILLISECONDS);
		Timer success = Timer.builder("http.server.requests")
			.tag("outcome", "SUCCESS")
			.tag("status", "200")
			.register(registry);
		success.record(100, TimeUnit.MILLISECONDS);
		Timer failure = Timer.builder("http.server.requests")
			.tag("outcome", "SERVER_ERROR")
			.tag("status", "500")
			.register(registry);
		failure.record(300, TimeUnit.MILLISECONDS);

		ReadinessService readinessService = mock(ReadinessService.class);
		when(readinessService.check()).thenReturn(ReadinessResponse.of(true, true));
		AdminAppMetricsResponse response = new AdminAppMetricsService(
			registry,
			readinessService,
			Clock.fixed(NOW, ZoneOffset.UTC)
		).metrics();

		assertThat(response.available()).isTrue();
		assertThat(response.jvm().heapUsedBytes()).isEqualTo(100);
		assertThat(response.jvm().heapMaxBytes()).isEqualTo(300);
		assertThat(response.jvm().heapCommittedBytes()).isEqualTo(200);
		assertThat(response.jvm().liveThreads()).isEqualTo(12);
		assertThat(response.jvm().gcCount()).isEqualTo(2);
		assertThat(response.http().requestCount()).isEqualTo(2);
		assertThat(response.http().serverErrorCount()).isEqualTo(1);
		assertThat(response.http().averageResponseTimeMs()).isEqualTo(200.0);
		assertThat(response.db().activeConnections()).isEqualTo(2);
		assertThat(response.db().idleConnections()).isEqualTo(8);
		assertThat(response.db().maxConnections()).isEqualTo(10);
		assertThat(response.uptimeSeconds()).isEqualTo(123.0);
		assertThat(response.aiService().status()).isEqualTo("UP");
		assertThat(response.aiService().checkedAt()).isEqualTo(NOW);
	}

	private void gauge(
		SimpleMeterRegistry registry,
		String name,
		long value,
		String... tags
	) {
		AtomicLong holder = new AtomicLong(value);
		Gauge.builder(name, holder, AtomicLong::doubleValue)
			.tags(tags)
			.strongReference(true)
			.register(registry);
	}
}
