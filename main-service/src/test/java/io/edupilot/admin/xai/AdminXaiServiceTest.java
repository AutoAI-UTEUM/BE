package io.edupilot.admin.xai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.admin.xai.XaiManagementClient.InvoicePreview;
import io.edupilot.admin.xai.XaiManagementClient.PrepaidBalance;
import io.edupilot.admin.xai.XaiManagementClient.SpendingLimits;
import io.edupilot.admin.xai.dto.AdminXaiCreditsResponse;
import io.edupilot.admin.xai.dto.AdminXaiOverviewResponse;

class AdminXaiServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

	private XaiManagementClient client;
	private MutableClock clock;
	private MutableTicker ticker;
	private AdminXaiService service;

	@BeforeEach
	void setUp() {
		client = mock(XaiManagementClient.class);
		clock = new MutableClock(NOW);
		ticker = new MutableTicker();
		stubSuccess();
		service = service(properties(true));
	}

	@Test
	void cachesEachEndpointByItsTtlAndSyncForcesAllReloads() {
		service.credits();
		service.credits();
		verify(client).fetchPrepaidBalance();
		verify(client).fetchSpendingLimits();
		verify(client).fetchInvoicePreview();

		advance(Duration.ofMinutes(2).plusNanos(1));
		service.credits();
		verify(client, times(2)).fetchPrepaidBalance();
		verify(client, times(2)).fetchSpendingLimits();
		verify(client).fetchInvoicePreview();

		service.sync();
		verify(client, times(3)).fetchPrepaidBalance();
		verify(client, times(3)).fetchSpendingLimits();
		verify(client, times(2)).fetchInvoicePreview();
	}

	@Test
	void returnsLastSuccessfulValuesAsStaleAfterRefreshFailure() {
		AdminXaiCreditsResponse first = service.credits();
		advance(Duration.ofMinutes(5).plusNanos(1));
		when(client.fetchPrepaidBalance()).thenThrow(temporaryFailure());
		when(client.fetchSpendingLimits()).thenThrow(temporaryFailure());
		when(client.fetchInvoicePreview()).thenThrow(temporaryFailure());

		AdminXaiCreditsResponse stale = service.credits();

		assertThat(stale.available()).isTrue();
		assertThat(stale.stale()).isTrue();
		assertThat(stale.prepaidBalanceUsd())
			.isEqualByComparingTo(first.prepaidBalanceUsd());
		assertThat(stale.postpaidLimitUsd())
			.isEqualByComparingTo(first.postpaidLimitUsd());
		assertThat(stale.postpaidUsedUsd())
			.isEqualByComparingTo(first.postpaidUsedUsd());
		assertThat(stale.fetchedAt()).isEqualTo(NOW);
		assertThat(service.status().lastFailureAt()).isEqualTo(clock.instant());
		assertThat(service.status().recentErrorClassification())
			.isEqualTo(XaiManagementFailureType.TEMPORARY_FAILURE);
	}

	@Test
	void returnsAvailableStaleNullDataWhenFirstQueryFails() {
		when(client.fetchPrepaidBalance()).thenThrow(configurationFailure());
		when(client.fetchSpendingLimits()).thenThrow(configurationFailure());
		when(client.fetchInvoicePreview()).thenThrow(configurationFailure());
		service = service(properties(true));

		AdminXaiCreditsResponse response = service.credits();

		assertThat(response.available()).isTrue();
		assertThat(response.stale()).isTrue();
		assertThat(response.prepaidBalanceUsd()).isNull();
		assertThat(response.postpaidLimitUsd()).isNull();
		assertThat(response.postpaidUsedUsd()).isNull();
		assertThat(response.fetchedAt()).isNull();
		assertThat(service.status().recentErrorClassification())
			.isEqualTo(XaiManagementFailureType.CONFIGURATION_ERROR);
	}

	@Test
	void missingConfigurationIsNormalUnavailableResponseWithoutClientCalls() {
		AdminXaiService disabled = service(properties(false));

		AdminXaiOverviewResponse response = disabled.overview();

		assertThat(response.available()).isFalse();
		assertThat(response.stale()).isNull();
		assertThat(response.prepaidBalanceUsd()).isNull();
		assertThat(disabled.status().available()).isFalse();
		verify(client, never()).fetchPrepaidBalance();
		verify(client, never()).fetchSpendingLimits();
		verify(client, never()).fetchInvoicePreview();
	}

	@Test
	void overviewUsesInvoicePeriodAverageAndProjectsDepletionWithoutDouble() {
		when(client.fetchPrepaidBalance())
			.thenReturn(new PrepaidBalance(new BigDecimal("100.00")));
		when(client.fetchSpendingLimits())
			.thenReturn(new SpendingLimits(new BigDecimal("200.00")));
		when(client.fetchInvoicePreview()).thenReturn(new InvoicePreview(
			new BigDecimal("100.00"),
			YearMonth.of(2026, 9)
		));

		AdminXaiOverviewResponse response = service.overview();

		assertThat(response.postpaidRemainingUsd()).isEqualByComparingTo("100.00");
		assertThat(response.totalAvailableUsd()).isEqualByComparingTo("200.00");
		assertThat(response.averageDailyCost7d()).isEqualByComparingTo("10.000000");
		assertThat(response.projectedDepletionAt())
			.isEqualTo(NOW.plus(Duration.ofDays(20)));
		assertThat(response.riskLevel()).hasToString("WARNING");
	}

	@Test
	void zeroAverageLeavesProjectionNullWhileBalanceStillDeterminesRisk() {
		when(client.fetchInvoicePreview()).thenReturn(new InvoicePreview(
			BigDecimal.ZERO,
			YearMonth.of(2026, 9)
		));

		AdminXaiOverviewResponse response = service.overview();

		assertThat(response.averageDailyCost7d()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(response.projectedDepletionAt()).isNull();
		assertThat(response.riskLevel()).hasToString("NORMAL");
	}

	private void stubSuccess() {
		when(client.fetchPrepaidBalance())
			.thenReturn(new PrepaidBalance(new BigDecimal("100.00")));
		when(client.fetchSpendingLimits())
			.thenReturn(new SpendingLimits(new BigDecimal("200.00")));
		when(client.fetchInvoicePreview()).thenReturn(new InvoicePreview(
			new BigDecimal("20.00"),
			YearMonth.of(2026, 9)
		));
	}

	private AdminXaiService service(XaiManagementProperties properties) {
		return new AdminXaiService(
			properties,
			client,
			new XaiRiskPolicy(),
			clock,
			ticker
		);
	}

	private XaiManagementProperties properties(boolean configured) {
		return new XaiManagementProperties(
			"https://management-api.x.ai",
			configured ? "secret" : "",
			configured ? "team" : "",
			Duration.ofSeconds(2),
			Duration.ofSeconds(5),
			Duration.ofMinutes(2),
			Duration.ofMinutes(2),
			Duration.ofMinutes(5),
			Duration.ofMinutes(1)
		);
	}

	private XaiManagementClientException temporaryFailure() {
		return new XaiManagementClientException(
			XaiManagementFailureType.TEMPORARY_FAILURE
		);
	}

	private XaiManagementClientException configurationFailure() {
		return new XaiManagementClientException(
			XaiManagementFailureType.CONFIGURATION_ERROR
		);
	}

	private void advance(Duration duration) {
		ticker.advance(duration);
		clock.advance(duration);
	}

	private static final class MutableTicker implements Ticker {

		private final AtomicLong nanos = new AtomicLong();

		@Override
		public long read() {
			return nanos.get();
		}

		private void advance(Duration duration) {
			nanos.addAndGet(duration.toNanos());
		}
	}

	private static final class MutableClock extends Clock {

		private Instant current;

		private MutableClock(Instant current) {
			this.current = current;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}

		private void advance(Duration duration) {
			current = current.plus(duration);
		}
	}
}
