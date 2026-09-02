package io.edupilot.admin.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.edupilot.admin.infra.dto.AdminInfraMetricsResponse;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;

class AdminInfraMetricsServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

	private CloudWatchClient cloudWatchClient;
	private MutableClock clock;
	private AdminInfraMetricsService service;

	@BeforeEach
	void setUp() {
		cloudWatchClient = mock(CloudWatchClient.class);
		clock = new MutableClock(NOW);
		service = new AdminInfraMetricsService(
			properties(true),
			Optional.of(cloudWatchClient),
			clock
		);
	}

	@Test
	void requestsSixMetricsOnceAndMapsPartialResults() {
		when(cloudWatchClient.getMetricData(any(GetMetricDataRequest.class)))
			.thenReturn(metricResponse());

		AdminInfraMetricsResponse response = service.metrics("prod", "1h");

		ArgumentCaptor<GetMetricDataRequest> requestCaptor =
			ArgumentCaptor.forClass(GetMetricDataRequest.class);
		verify(cloudWatchClient).getMetricData(requestCaptor.capture());
		GetMetricDataRequest request = requestCaptor.getValue();
		assertThat(request.startTime()).isEqualTo(NOW.minus(Duration.ofHours(1)));
		assertThat(request.endTime()).isEqualTo(NOW);
		assertThat(request.metricDataQueries()).hasSize(6);

		Map<String, MetricDataQuery> queries = request.metricDataQueries().stream()
			.collect(Collectors.toMap(MetricDataQuery::id, Function.identity()));
		assertThat(queries.keySet()).containsExactlyInAnyOrder(
			"cpu",
			"netIn",
			"netOut",
			"mem",
			"disk",
			"status"
		);
		assertThat(List.of("cpu", "netIn", "netOut", "mem", "status"))
			.allSatisfy(id -> {
				MetricDataQuery query = queries.get(id);
				assertThat(query.metricStat().period()).isEqualTo(60);
				assertThat(query.metricStat().metric().dimensions())
					.singleElement()
					.satisfies(dimension -> {
						assertThat(dimension.name()).isEqualTo("InstanceId");
						assertThat(dimension.value()).isEqualTo("i-prod");
					});
			});
		MetricDataQuery diskQuery = queries.get("disk");
		assertThat(diskQuery.metricStat()).isNull();
		assertThat(diskQuery.expression())
			.contains("{CWAgent,InstanceId,device,fstype,path}")
			.contains("MetricName=\"disk_used_percent\"")
			.contains("InstanceId=\"i-prod\"")
			.contains("path=\"/\"")
			.contains("'Maximum', 60")
			.doesNotContain("device=\"")
			.doesNotContain("fstype=\"")
			.doesNotStartWith("MAX(");

		assertThat(response.available()).isTrue();
		assertThat(response.periodSeconds()).isEqualTo(60);
		assertThat(response.series().cpu())
			.extracting(AdminInfraMetricsResponse.Point::v)
			.containsExactly(10.0, 20.0);
		assertThat(response.series().mem()).isEmpty();
		assertThat(response.latest().cpu()).isEqualTo(20.0);
		assertThat(response.latest().mem()).isNull();
	}

	@Test
	void mergesMultipleDiskSeriesUsingMaximumValuePerTimestamp() {
		when(cloudWatchClient.getMetricData(any(GetMetricDataRequest.class)))
			.thenReturn(GetMetricDataResponse.builder()
				.metricDataResults(
					MetricDataResult.builder()
						.id("disk")
						.timestamps(
							NOW.minusSeconds(600),
							NOW.minusSeconds(300)
						)
						.values(40.0, 60.0)
						.build(),
					MetricDataResult.builder()
						.id("disk")
						.timestamps(
							NOW.minusSeconds(600),
							NOW.minusSeconds(300),
							NOW
						)
						.values(50.0, 55.0, 70.0)
						.build()
				)
				.build());

		AdminInfraMetricsResponse response = service.metrics("prod", "1h");

		assertThat(response.series().disk())
			.extracting(AdminInfraMetricsResponse.Point::v)
			.containsExactly(50.0, 60.0, 70.0);
		assertThat(response.latest().disk()).isEqualTo(70.0);
	}

	@Test
	void rejectsInvalidInstanceIdBeforeBuildingSearchExpression() {
		AdminInfraMetricsService invalidInstanceService =
			new AdminInfraMetricsService(
				properties(true, "i-prod\" path=\"*"),
				Optional.of(cloudWatchClient),
				clock
			);

		AdminInfraMetricsResponse response = invalidInstanceService.metrics(
			"prod",
			"1h"
		);

		assertThat(response.available()).isFalse();
		assertThat(response.reason()).isEqualTo("INSTANCE_ID_INVALID");
		verifyNoInteractions(cloudWatchClient);
	}

	@Test
	void cachesUntilTtlAndReloadsAfterExpiry() {
		when(cloudWatchClient.getMetricData(any(GetMetricDataRequest.class)))
			.thenReturn(metricResponse());

		service.metrics("prod", "24h");
		service.metrics("prod", "24h");
		verify(cloudWatchClient).getMetricData(any(GetMetricDataRequest.class));

		clock.advance(Duration.ofMinutes(5).plusSeconds(1));
		service.metrics("prod", "24h");
		verify(cloudWatchClient, times(2))
			.getMetricData(any(GetMetricDataRequest.class));
	}

	@Test
	void returnsStaleSuccessAfterRefreshFailureAndUnavailableWithoutCache() {
		when(cloudWatchClient.getMetricData(any(GetMetricDataRequest.class)))
			.thenReturn(metricResponse())
			.thenThrow(new RuntimeException("AWS unavailable"));

		AdminInfraMetricsResponse first = service.metrics("prod", "6h");
		clock.advance(Duration.ofMinutes(5).plusSeconds(1));
		AdminInfraMetricsResponse stale = service.metrics("prod", "6h");

		assertThat(first.available()).isTrue();
		assertThat(stale.available()).isTrue();
		assertThat(stale.stale()).isTrue();
		assertThat(stale.reason()).isEqualTo("AWS_ERROR");
		assertThat(stale.series()).isEqualTo(first.series());

		CloudWatchClient failingClient = mock(CloudWatchClient.class);
		when(failingClient.getMetricData(any(GetMetricDataRequest.class)))
			.thenThrow(new RuntimeException("AWS unavailable"));
		AdminInfraMetricsResponse unavailable = new AdminInfraMetricsService(
			properties(true),
			Optional.of(failingClient),
			clock
		).metrics("dev", "7d");
		assertThat(unavailable.available()).isFalse();
		assertThat(unavailable.reason()).isEqualTo("AWS_ERROR");
	}

	private GetMetricDataResponse metricResponse() {
		return GetMetricDataResponse.builder()
			.metricDataResults(
				MetricDataResult.builder()
					.id("cpu")
					.timestamps(NOW.minusSeconds(300), NOW.minusSeconds(600))
					.values(20.0, 10.0)
					.build(),
				MetricDataResult.builder()
					.id("status")
					.timestamps(NOW.minusSeconds(300))
					.values(0.0)
					.build()
			)
			.build();
	}

	private AdminInfraProperties properties(boolean enabled) {
		return properties(enabled, "i-prod");
	}

	private AdminInfraProperties properties(
		boolean enabled,
		String prodInstanceId
	) {
		return new AdminInfraProperties(
			enabled,
			"ap-northeast-2",
			new AdminInfraProperties.Instances(prodInstanceId, "i-dev"),
			Duration.ofMinutes(5),
			Duration.ofHours(12)
		);
	}
}
