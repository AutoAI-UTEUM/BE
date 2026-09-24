package io.edupilot.admin.xai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.edupilot.admin.xai.dto.XaiUsageGranularity;
import io.edupilot.admin.xai.dto.XaiUsageGroupBy;
import io.edupilot.admin.xai.dto.XaiUsageMetric;
import io.edupilot.aiusage.AiUsageLogRepository;
import io.edupilot.aiusage.AiUsageLogRepository.XaiCostSummaryProjection;
import io.edupilot.aiusage.AiUsageLogRepository.XaiUsageProjection;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

class AdminXaiUsageServiceTest {

	private static final LocalDate DATE = LocalDate.of(2026, 9, 1);

	private AiUsageLogRepository repository;
	private AdminXaiUsageService service;

	@BeforeEach
	void setUp() {
		repository = mock(AiUsageLogRepository.class);
		service = new AdminXaiUsageService(repository);
	}

	@Test
	void aggregatesCostTokensAndCallsWithoutTreatingUnknownCostAsZero() {
		XaiUsageProjection row = usageRow();
		when(repository.aggregateXaiUsageByFeature(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(List.of(row));

		var cost = service.usage(
			DATE,
			DATE,
			XaiUsageGranularity.DAY,
			XaiUsageMetric.COST,
			XaiUsageGroupBy.FEATURE
		);
		var tokens = service.usage(
			DATE,
			DATE,
			XaiUsageGranularity.DAY,
			XaiUsageMetric.TOKENS,
			XaiUsageGroupBy.FEATURE
		);
		var calls = service.usage(
			DATE,
			DATE,
			XaiUsageGranularity.DAY,
			XaiUsageMetric.CALLS,
			XaiUsageGroupBy.FEATURE
		);

		assertThat(cost.unknownCostCalls()).isEqualTo(1);
		assertThat(cost.items()).singleElement().satisfies(point -> {
			assertThat(point.date()).isEqualTo(DATE);
			assertThat(point.group()).isEqualTo("TURN");
			assertThat(point.costUsd()).isEqualByComparingTo("2.5");
			assertThat(point.tokenCount()).isNull();
			assertThat(point.callCount()).isNull();
		});
		assertThat(tokens.items().getFirst().tokenCount()).isEqualTo(42L);
		assertThat(tokens.items().getFirst().costUsd()).isNull();
		assertThat(calls.items().getFirst().callCount()).isEqualTo(3L);
	}

	@Test
	void usesModelQueryAndConvertsKstDateBoundariesToUtc() {
		when(repository.aggregateXaiUsageByModel(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(List.of());

		service.usage(
			DATE,
			DATE,
			XaiUsageGranularity.DAY,
			XaiUsageMetric.CALLS,
			XaiUsageGroupBy.MODEL
		);

		ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(
			LocalDateTime.class
		);
		ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(
			LocalDateTime.class
		);
		verify(repository).aggregateXaiUsageByModel(
			from.capture(),
			to.capture()
		);
		assertThat(from.getValue()).isEqualTo("2026-08-31T15:00:00");
		assertThat(to.getValue()).isEqualTo("2026-09-01T15:00:00");
	}

	@Test
	void rejectsApiKeyGroupingWithStableErrorCode() {
		assertThatThrownBy(() -> service.usage(
			DATE,
			DATE,
			XaiUsageGranularity.DAY,
			XaiUsageMetric.COST,
			XaiUsageGroupBy.API_KEY
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.UNSUPPORTED_GROUP_BY)
		);
	}

	@Test
	void returnsNullCostAndUnknownCoverageWhenEveryCostIsMissing() {
		XaiCostSummaryProjection row = mock(XaiCostSummaryProjection.class);
		when(row.getKnownCostCalls()).thenReturn(0L);
		when(row.getUnknownCostCalls()).thenReturn(4L);
		when(repository.summarizeXaiCost(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(row);

		var summary = service.costSummary(DATE, DATE);

		assertThat(summary.costUsd()).isNull();
		assertThat(summary.knownCostCalls()).isZero();
		assertThat(summary.unknownCostCalls()).isEqualTo(4);
	}

	private XaiUsageProjection usageRow() {
		XaiUsageProjection row = mock(XaiUsageProjection.class);
		when(row.getUsageDate()).thenReturn(DATE);
		when(row.getGroupKey()).thenReturn("TURN");
		when(row.getCallCount()).thenReturn(3L);
		when(row.getTokenCount()).thenReturn(42L);
		when(row.getCostUsdTicks()).thenReturn(
			new BigDecimal("25000000000")
		);
		when(row.getUnknownCostCalls()).thenReturn(1L);
		return row;
	}
}
