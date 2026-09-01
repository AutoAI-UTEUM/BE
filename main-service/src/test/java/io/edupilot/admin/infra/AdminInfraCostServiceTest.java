package io.edupilot.admin.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.edupilot.admin.infra.dto.AdminInfraCostResponse;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse;
import software.amazon.awssdk.services.costexplorer.model.Group;
import software.amazon.awssdk.services.costexplorer.model.MetricValue;
import software.amazon.awssdk.services.costexplorer.model.ResultByTime;

class AdminInfraCostServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

	private CostExplorerClient costExplorerClient;
	private MutableClock clock;
	private AdminInfraCostService service;

	@BeforeEach
	void setUp() {
		costExplorerClient = mock(CostExplorerClient.class);
		clock = new MutableClock(NOW);
		service = new AdminInfraCostService(
			properties(true),
			Optional.of(costExplorerClient),
			clock
		);
	}

	@Test
	void requestsDailyServiceGroupsAndBuildsTopTenPlusOther() {
		when(costExplorerClient.getCostAndUsage(any(GetCostAndUsageRequest.class)))
			.thenReturn(costResponse());

		AdminInfraCostResponse response = service.cost();

		ArgumentCaptor<GetCostAndUsageRequest> requestCaptor =
			ArgumentCaptor.forClass(GetCostAndUsageRequest.class);
		verify(costExplorerClient).getCostAndUsage(requestCaptor.capture());
		GetCostAndUsageRequest request = requestCaptor.getValue();
		assertThat(request.timePeriod().start()).isEqualTo("2026-08-03");
		assertThat(request.timePeriod().end()).isEqualTo("2026-09-02");
		assertThat(request.granularityAsString()).isEqualTo("DAILY");
		assertThat(request.metrics()).containsExactly("UnblendedCost");
		assertThat(request.groupBy()).singleElement().satisfies(group -> {
			assertThat(group.typeAsString()).isEqualTo("DIMENSION");
			assertThat(group.key()).isEqualTo("SERVICE");
		});

		assertThat(response.available()).isTrue();
		assertThat(response.currency()).isEqualTo("USD");
		assertThat(response.monthToDate().total())
			.isEqualByComparingTo(new BigDecimal("78"));
		assertThat(response.monthToDate().byService()).hasSize(11);
		assertThat(response.monthToDate().byService().get(0).service())
			.isEqualTo("Service-12");
		assertThat(response.monthToDate().byService().get(10).service())
			.isEqualTo("Other");
		assertThat(response.monthToDate().byService().get(10).amount())
			.isEqualByComparingTo(new BigDecimal("3"));
		assertThat(response.daily()).singleElement().satisfies(day -> {
			assertThat(day.date()).hasToString("2026-09-01");
			assertThat(day.total()).isEqualByComparingTo(new BigDecimal("78"));
		});
	}

	@Test
	void cachesUntilTtlAndReloadsAfterExpiry() {
		when(costExplorerClient.getCostAndUsage(any(GetCostAndUsageRequest.class)))
			.thenReturn(costResponse());

		service.cost();
		service.cost();
		verify(costExplorerClient).getCostAndUsage(any(GetCostAndUsageRequest.class));

		clock.advance(Duration.ofHours(12).plusSeconds(1));
		service.cost();
		verify(costExplorerClient, times(2))
			.getCostAndUsage(any(GetCostAndUsageRequest.class));
	}

	@Test
	void returnsStaleCostAfterRefreshFailureAndUnavailableWithoutCache() {
		when(costExplorerClient.getCostAndUsage(any(GetCostAndUsageRequest.class)))
			.thenReturn(costResponse())
			.thenThrow(new RuntimeException("Cost Explorer unavailable"));

		AdminInfraCostResponse first = service.cost();
		clock.advance(Duration.ofHours(12).plusSeconds(1));
		AdminInfraCostResponse stale = service.cost();

		assertThat(stale.available()).isTrue();
		assertThat(stale.stale()).isTrue();
		assertThat(stale.reason()).isEqualTo("AWS_ERROR");
		assertThat(stale.monthToDate()).isEqualTo(first.monthToDate());

		CostExplorerClient failingClient = mock(CostExplorerClient.class);
		when(failingClient.getCostAndUsage(any(GetCostAndUsageRequest.class)))
			.thenThrow(new RuntimeException("Cost Explorer unavailable"));
		AdminInfraCostResponse unavailable = new AdminInfraCostService(
			properties(true),
			Optional.of(failingClient),
			clock
		).cost();
		assertThat(unavailable.available()).isFalse();
		assertThat(unavailable.reason()).isEqualTo("AWS_ERROR");
	}

	private GetCostAndUsageResponse costResponse() {
		List<Group> groups = new ArrayList<>();
		for (int index = 1; index <= 12; index++) {
			groups.add(Group.builder()
				.keys("Service-" + index)
				.metrics(Map.of(
					"UnblendedCost",
					MetricValue.builder()
						.amount(String.valueOf(index))
						.unit("USD")
						.build()
				))
				.build());
		}
		return GetCostAndUsageResponse.builder()
			.resultsByTime(ResultByTime.builder()
				.timePeriod(DateInterval.builder()
					.start("2026-09-01")
					.end("2026-09-02")
					.build())
				.groups(groups)
				.build())
			.build();
	}

	private AdminInfraProperties properties(boolean enabled) {
		return new AdminInfraProperties(
			enabled,
			"ap-northeast-2",
			new AdminInfraProperties.Instances("i-prod", "i-dev"),
			Duration.ofMinutes(5),
			Duration.ofHours(12)
		);
	}
}
