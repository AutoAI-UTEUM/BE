package io.edupilot.admin.xai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.admin.xai.XaiManagementClient.InvoicePreview;
import io.edupilot.admin.xai.XaiManagementClient.InvoiceSummary;
import io.edupilot.admin.xai.XaiManagementClient.PrepaidBalance;
import io.edupilot.admin.xai.XaiManagementClient.SpendingLimits;
import io.edupilot.admin.xai.dto.AdminXaiCreditsResponse;
import io.edupilot.admin.xai.dto.AdminXaiOverviewResponse;
import io.edupilot.admin.xai.dto.XaiCostSource;

class AdminXaiServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

	private XaiManagementClient client;
	private MutableClock clock;
	private MutableTicker ticker;
	private XaiAlertConfigService alertConfigService;
	private AdminXaiUsageService usageService;
	private AdminXaiService service;

	@BeforeEach
	void setUp() {
		client = mock(XaiManagementClient.class);
		clock = new MutableClock(NOW);
		ticker = new MutableTicker();
		alertConfigService = mock(XaiAlertConfigService.class);
		usageService = mock(AdminXaiUsageService.class);
		when(alertConfigService.currentThresholds())
			.thenReturn(XaiAlertThresholds.defaults());
		when(usageService.costSummary(any(Instant.class), any(Instant.class)))
			.thenReturn(new AdminXaiUsageService.CostSummary(null, 0, 0));
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
		assertThat(stale.prepaidAvailableUsd())
			.isEqualByComparingTo(first.prepaidAvailableUsd());
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
		assertThat(response.prepaidAvailableUsd()).isNull();
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
			new BigDecimal("40.00"),
			YearMonth.of(2026, 9)
		));

		AdminXaiOverviewResponse response = service.overview();

		assertThat(response.postpaidRemainingUsd()).isEqualByComparingTo("140.00");
		assertThat(response.totalAvailableUsd()).isEqualByComparingTo("200.00");
		assertThat(response.averageDailyCost7d()).isEqualByComparingTo("10.000000");
		assertThat(response.costSource()).isEqualTo(XaiCostSource.INVOICE_PREVIEW);
		assertThat(response.projectedDepletionAt())
			.isEqualTo(NOW.plus(Duration.ofDays(20)));
		assertThat(response.riskLevel()).hasToString("WARNING");
	}

	@Test
	void zeroAverageLeavesProjectionNullWhileBalanceStillDeterminesRisk() {
		when(client.fetchInvoicePreview()).thenReturn(new InvoicePreview(
			BigDecimal.ZERO,
			BigDecimal.ZERO,
			YearMonth.of(2026, 9)
		));

		AdminXaiOverviewResponse response = service.overview();

		assertThat(response.averageDailyCost7d()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(response.projectedDepletionAt()).isNull();
		assertThat(response.riskLevel()).hasToString("NORMAL");
	}

	@Test
	void separatesLedgerBalanceFromCurrentPrepaidDeductionForZeroLimitTeam() {
		when(client.fetchPrepaidBalance())
			.thenReturn(new PrepaidBalance(new BigDecimal("93.91")));
		when(client.fetchSpendingLimits())
			.thenReturn(new SpendingLimits(BigDecimal.ZERO));
		when(client.fetchInvoicePreview()).thenReturn(new InvoicePreview(
			new BigDecimal("37.14"),
			new BigDecimal("37.14"),
			YearMonth.of(2026, 9)
		));
		when(alertConfigService.currentThresholds()).thenReturn(
			new XaiAlertThresholds(
				new BigDecimal("50.00"),
				new BigDecimal("60.00"),
				1,
				2
			)
		);

		AdminXaiCreditsResponse credits = service.credits();
		AdminXaiOverviewResponse overview = service.overview();

		assertThat(credits.prepaidBalanceUsd()).isEqualByComparingTo("93.91");
		assertThat(credits.prepaidUsedThisPeriodUsd())
			.isEqualByComparingTo("37.14");
		assertThat(credits.prepaidAvailableUsd()).isEqualByComparingTo("56.77");
		assertThat(credits.currentMonthCostUsd()).isEqualByComparingTo("37.14");
		assertThat(credits.postpaidUsedUsd()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(credits.prepaidUsedThisPeriodUsd()
			.add(credits.postpaidUsedUsd()))
			.isEqualByComparingTo(credits.currentMonthCostUsd());
		assertThat(credits.postpaidRemainingUsd())
			.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(overview.totalAvailableUsd()).isEqualByComparingTo("56.77");
		assertThat(overview.projectedDepletionAt())
			.isAfter(NOW.plus(Duration.ofDays(15)))
			.isBefore(NOW.plus(Duration.ofDays(16)));
		assertThat(overview.riskLevel()).hasToString("WARNING");
	}

	@Test
	void missingPrepaidDeductionKeepsFreshLedgerButLeavesDerivedAmountsNull() {
		when(client.fetchPrepaidBalance())
			.thenReturn(new PrepaidBalance(new BigDecimal("93.91")));
		when(client.fetchInvoicePreview()).thenReturn(new InvoicePreview(
			new BigDecimal("37.14"),
			null,
			YearMonth.of(2026, 9)
		));

		AdminXaiOverviewResponse response = service.overview();

		assertThat(response.stale()).isFalse();
		assertThat(response.prepaidBalanceUsd()).isEqualByComparingTo("93.91");
		assertThat(response.currentMonthCostUsd()).isEqualByComparingTo("37.14");
		assertThat(response.prepaidUsedThisPeriodUsd()).isNull();
		assertThat(response.prepaidAvailableUsd()).isNull();
		assertThat(response.postpaidUsedUsd()).isNull();
		assertThat(response.postpaidRemainingUsd()).isNull();
		assertThat(response.totalAvailableUsd()).isNull();
		assertThat(response.projectedDepletionAt()).isNull();
		assertThat(response.riskLevel()).isNull();
	}

	@Test
	void overviewPrefersKnownInternalSevenDayCostAndConfiguredThresholds() {
		when(usageService.costSummary(any(Instant.class), any(Instant.class)))
			.thenReturn(new AdminXaiUsageService.CostSummary(
				new BigDecimal("70"),
				7,
				2
			));
		when(alertConfigService.currentThresholds()).thenReturn(
			new XaiAlertThresholds(
				new BigDecimal("300"),
				new BigDecimal("400"),
				2,
				5
			)
		);

		AdminXaiOverviewResponse response = service.overview();

		assertThat(response.averageDailyCost7d()).isEqualByComparingTo("10");
		assertThat(response.costSource()).isEqualTo(XaiCostSource.INTERNAL);
		assertThat(response.riskLevel()).hasToString("CRITICAL");
	}

	@Test
	void invoicesUseOneHourCacheAndReturnLastSuccessAsStale() {
		YearMonth period = YearMonth.of(2026, 8);
		when(client.fetchInvoices(period)).thenReturn(List.of(
			new InvoiceSummary(
				period,
				new BigDecimal("12.34"),
				new BigDecimal("56.78"),
				"PAID"
			)
		));

		service.invoices(period);
		var cached = service.invoices(period);

		verify(client).fetchInvoices(period);
		assertThat(cached.items()).singleElement().satisfies(invoice -> {
			assertThat(invoice.amountUsd()).isEqualByComparingTo("12.34");
			assertThat(invoice.status()).isEqualTo("PAID");
		});
		advance(Duration.ofHours(1).plusNanos(1));
		when(client.fetchInvoices(period)).thenThrow(temporaryFailure());

		var stale = service.invoices(period);

		verify(client, times(2)).fetchInvoices(period);
		assertThat(stale.stale()).isTrue();
		assertThat(stale.items()).singleElement()
			.extracting(invoice -> invoice.amountUsd())
			.isEqualTo(new BigDecimal("12.34"));
	}

	@Test
	void reconciliationMapsPartialDatesToWholeBillingMonthWithoutProration() {
		LocalDate from = LocalDate.of(2026, 8, 10);
		LocalDate to = LocalDate.of(2026, 8, 20);
		YearMonth period = YearMonth.of(2026, 8);
		when(usageService.costSummary(from, to)).thenReturn(
			new AdminXaiUsageService.CostSummary(
				new BigDecimal("8.00"),
				2,
				1
			)
		);
		when(client.fetchInvoices(period)).thenReturn(List.of(
			new InvoiceSummary(
				period,
				new BigDecimal("4.00"),
				new BigDecimal("10.00"),
				"PAID"
			)
		));

		var response = service.reconciliation(from, to);

		assertThat(response.xaiPeriodFrom()).isEqualTo("2026-08-01");
		assertThat(response.xaiPeriodTo()).isEqualTo("2026-08-31");
		assertThat(response.internalCostUsd()).isEqualByComparingTo("8");
		assertThat(response.xaiBilledUsd()).isEqualByComparingTo("10");
		assertThat(response.differenceUsd()).isEqualByComparingTo("-2");
		assertThat(response.differenceRatio()).isEqualByComparingTo("-0.2");
		assertThat(response.unknownCostCalls()).isEqualTo(1);
		assertThat(response.coverageNote()).contains("일할 계산하지");
	}

	private void stubSuccess() {
		when(client.fetchPrepaidBalance())
			.thenReturn(new PrepaidBalance(new BigDecimal("100.00")));
		when(client.fetchSpendingLimits())
			.thenReturn(new SpendingLimits(new BigDecimal("200.00")));
		when(client.fetchInvoicePreview()).thenReturn(new InvoicePreview(
			new BigDecimal("20.00"),
			new BigDecimal("10.00"),
			YearMonth.of(2026, 9)
		));
	}

	private AdminXaiService service(XaiManagementProperties properties) {
		return new AdminXaiService(
			properties,
			client,
			new XaiRiskPolicy(),
			alertConfigService,
			usageService,
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
			Duration.ofHours(1),
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
