package io.edupilot.admin.xai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.admin.xai.XaiManagementClient.InvoicePreview;
import io.edupilot.admin.xai.XaiManagementClient.InvoiceSummary;
import io.edupilot.admin.xai.XaiManagementClient.PrepaidBalance;
import io.edupilot.admin.xai.XaiManagementClient.SpendingLimits;
import io.edupilot.admin.xai.dto.AdminXaiCreditsResponse;
import io.edupilot.admin.xai.dto.AdminXaiInvoiceResponse;
import io.edupilot.admin.xai.dto.AdminXaiInvoicesResponse;
import io.edupilot.admin.xai.dto.AdminXaiOverviewResponse;
import io.edupilot.admin.xai.dto.AdminXaiReconciliationResponse;
import io.edupilot.admin.xai.dto.AdminXaiStatusResponse;
import io.edupilot.admin.xai.dto.XaiCostSource;
import io.edupilot.admin.xai.dto.XaiRiskLevel;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Service
public class AdminXaiService {

	private static final Logger log = LoggerFactory.getLogger(
		AdminXaiService.class
	);
	private static final String CACHE_KEY = "value";
	private static final BigDecimal SECONDS_PER_DAY =
		BigDecimal.valueOf(86_400L);

	private final XaiManagementProperties properties;
	private final XaiManagementClient client;
	private final XaiRiskPolicy riskPolicy;
	private final XaiAlertConfigService alertConfigService;
	private final AdminXaiUsageService usageService;
	private final Clock clock;
	private final Cache<String, CachedValue<PrepaidBalance>> balanceCache;
	private final Cache<String, CachedValue<SpendingLimits>> limitsCache;
	private final Cache<String, CachedValue<InvoicePreview>> invoiceCache;
	private final Cache<YearMonth, CachedValue<List<InvoiceSummary>>>
		historicalInvoiceCache;

	private CachedValue<PrepaidBalance> lastBalance;
	private CachedValue<SpendingLimits> lastLimits;
	private CachedValue<InvoicePreview> lastInvoice;
	private final Map<YearMonth, CachedValue<List<InvoiceSummary>>>
		lastHistoricalInvoices = new HashMap<>();
	private Instant lastSuccessfulSyncAt;
	private Instant lastFailureAt;
	private XaiManagementFailureType recentErrorClassification;

	@Autowired
	public AdminXaiService(
		XaiManagementProperties properties,
		XaiManagementClient client,
		XaiRiskPolicy riskPolicy,
		XaiAlertConfigService alertConfigService,
		AdminXaiUsageService usageService,
		Clock clock
	) {
		this(
			properties,
			client,
			riskPolicy,
			alertConfigService,
			usageService,
			clock,
			Ticker.systemTicker()
		);
	}

	AdminXaiService(
		XaiManagementProperties properties,
		XaiManagementClient client,
		XaiRiskPolicy riskPolicy,
		XaiAlertConfigService alertConfigService,
		AdminXaiUsageService usageService,
		Clock clock,
		Ticker ticker
	) {
		this.properties = properties;
		this.client = client;
		this.riskPolicy = riskPolicy;
		this.alertConfigService = alertConfigService;
		this.usageService = usageService;
		this.clock = clock;
		this.balanceCache = cache(properties.balanceCacheTtl(), ticker);
		this.limitsCache = cache(properties.limitsCacheTtl(), ticker);
		this.invoiceCache = cache(properties.invoiceCacheTtl(), ticker);
		this.historicalInvoiceCache = Caffeine.newBuilder()
			.maximumSize(24)
			.expireAfterWrite(properties.historicalInvoiceCacheTtl())
			.ticker(ticker)
			.build();
	}

	public AdminXaiCreditsResponse credits() {
		Snapshot snapshot = snapshot(false);
		if (!snapshot.available()) {
			return AdminXaiCreditsResponse.unavailable();
		}
		BillingAmounts amounts = billingAmounts(snapshot);
		return new AdminXaiCreditsResponse(
			amounts.prepaidBalanceUsd(),
			amounts.prepaidUsedThisPeriodUsd(),
			amounts.prepaidAvailableUsd(),
			amounts.currentMonthCostUsd(),
			amounts.postpaidLimitUsd(),
			amounts.postpaidUsedUsd(),
			amounts.postpaidRemainingUsd(),
			snapshot.fetchedAt(),
			snapshot.lastSuccessfulSyncAt(),
			snapshot.stale(),
			true
		);
	}

	public AdminXaiOverviewResponse overview() {
		return overview(snapshot(false));
	}

	public AdminXaiOverviewResponse sync() {
		return overview(snapshot(true));
	}

	public synchronized AdminXaiStatusResponse status() {
		if (!properties.configured()) {
			return AdminXaiStatusResponse.unavailable();
		}
		return new AdminXaiStatusResponse(
			true,
			lastSuccessfulSyncAt,
			lastFailureAt,
			recentErrorClassification
		);
	}

	public AdminXaiInvoicesResponse invoices(YearMonth period) {
		if (!properties.configured()) {
			return AdminXaiInvoicesResponse.unavailable(period);
		}
		Fetch<List<InvoiceSummary>> fetch = historicalInvoices(period);
		if (fetch.failureType() != null) {
			recordFailures(List.of(fetch.failureType()), clock.instant());
		}
		List<AdminXaiInvoiceResponse> items = fetch.cachedValue() == null
			? null
			: fetch.cachedValue().value().stream()
				.map(this::invoiceResponse)
				.toList();
		return new AdminXaiInvoicesResponse(
			period,
			items,
			fetch.cachedValue() == null
				? null
				: fetch.cachedValue().fetchedAt(),
			lastSuccessfulSyncAt,
			fetch.stale(),
			true
		);
	}

	public AdminXaiReconciliationResponse reconciliation(
		LocalDate from,
		LocalDate to
	) {
		if (from == null || to == null || from.isAfter(to)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		AdminXaiUsageService.CostSummary internal = usageService.costSummary(
			from,
			to
		);
		YearMonth firstMonth = YearMonth.from(from);
		YearMonth lastMonth = YearMonth.from(to);
		String coverageNote = "xAI 금액은 요청 기간이 걸친 과거 월의 확정 "
			+ "invoice와 현재 월의 invoice preview를 사용하며 일할 계산하지 "
			+ "않습니다.";
		if (!properties.configured()) {
			return new AdminXaiReconciliationResponse(
				from,
				to,
				firstMonth.atDay(1),
				lastMonth.atEndOfMonth(),
				internal.costUsd(),
				null,
				null,
				null,
				internal.unknownCostCalls(),
				coverageNote,
				null,
				false
			);
		}

		BillingTotal billing = billedTotal(firstMonth, lastMonth);
		BigDecimal difference = billing.totalUsd() == null
			|| internal.costUsd() == null
			? null
			: internal.costUsd().subtract(billing.totalUsd());
		BigDecimal differenceRatio = difference == null
			|| billing.totalUsd().signum() == 0
			? null
			: difference.divide(
				billing.totalUsd(),
				8,
				RoundingMode.HALF_UP
			);
		return new AdminXaiReconciliationResponse(
			from,
			to,
			firstMonth.atDay(1),
			lastMonth.atEndOfMonth(),
			internal.costUsd(),
			billing.totalUsd(),
			difference,
			differenceRatio,
			internal.unknownCostCalls(),
			coverageNote,
			billing.stale(),
			true
		);
	}

	private AdminXaiOverviewResponse overview(Snapshot snapshot) {
		if (!snapshot.available()) {
			return AdminXaiOverviewResponse.unavailable();
		}
		Instant now = clock.instant();
		BillingAmounts amounts = billingAmounts(snapshot);
		AverageCost averageCost = averageDailyCost(snapshot.invoice(), now);
		BigDecimal averageDailyCost = averageCost.amount();
		Instant projectedDepletionAt = projectedDepletionAt(
			amounts.totalAvailableUsd(),
			averageDailyCost,
			now
		);
		XaiRiskLevel riskLevel = amounts.totalAvailableUsd() == null
			? null
			: riskPolicy.assess(
				amounts.totalAvailableUsd(),
				projectedDepletionAt,
				now,
				alertConfigService.currentThresholds()
			);
		return new AdminXaiOverviewResponse(
			amounts.prepaidBalanceUsd(),
			amounts.prepaidUsedThisPeriodUsd(),
			amounts.prepaidAvailableUsd(),
			amounts.currentMonthCostUsd(),
			amounts.postpaidLimitUsd(),
			amounts.postpaidUsedUsd(),
			amounts.postpaidRemainingUsd(),
			amounts.totalAvailableUsd(),
			averageDailyCost,
			averageCost.source(),
			projectedDepletionAt,
			riskLevel,
			snapshot.fetchedAt(),
			snapshot.lastSuccessfulSyncAt(),
			snapshot.stale(),
			true
		);
	}

	private synchronized Snapshot snapshot(boolean forceRefresh) {
		if (!properties.configured()) {
			return Snapshot.unavailable();
		}
		if (forceRefresh) {
			balanceCache.invalidateAll();
			limitsCache.invalidateAll();
			invoiceCache.invalidateAll();
			historicalInvoiceCache.invalidateAll();
		}

		Instant now = clock.instant();
		Fetch<PrepaidBalance> balance = load(
			balanceCache,
			client::fetchPrepaidBalance,
			() -> lastBalance,
			value -> lastBalance = value,
			now
		);
		Fetch<SpendingLimits> limits = load(
			limitsCache,
			client::fetchSpendingLimits,
			() -> lastLimits,
			value -> lastLimits = value,
			now
		);
		Fetch<InvoicePreview> invoice = load(
			invoiceCache,
			client::fetchInvoicePreview,
			() -> lastInvoice,
			value -> lastInvoice = value,
			now
		);

		List<XaiManagementFailureType> failures = new ArrayList<>();
		addFailure(failures, balance.failureType());
		addFailure(failures, limits.failureType());
		addFailure(failures, invoice.failureType());
		if (!failures.isEmpty()) {
			recordFailures(failures, now);
		} else if (balance.loaded() || limits.loaded() || invoice.loaded()) {
			recentErrorClassification = null;
		}

		return new Snapshot(
			true,
			balance.cachedValue(),
			limits.cachedValue(),
			invoice.cachedValue(),
			balance.stale() || limits.stale() || invoice.stale(),
			oldestSuccess(balance, limits, invoice),
			lastSuccessfulSyncAt
		);
	}

	private BillingAmounts billingAmounts(Snapshot snapshot) {
		BigDecimal prepaidBalance = value(
			snapshot.balance(),
			PrepaidBalance::balanceUsd
		);
		BigDecimal prepaidUsed = value(
			snapshot.invoice(),
			InvoicePreview::prepaidUsedThisPeriodUsd
		);
		BigDecimal currentCost = value(
			snapshot.invoice(),
			InvoicePreview::currentMonthCostUsd
		);
		BigDecimal postpaidLimit = value(
			snapshot.limits(),
			SpendingLimits::effectiveLimitUsd
		);
		BigDecimal postpaidUsed = value(
			snapshot.invoice(),
			InvoicePreview::postpaidUsedUsd
		);
		BigDecimal prepaidAvailable = prepaidBalance == null || prepaidUsed == null
			? null
			: prepaidBalance.subtract(prepaidUsed);
		BigDecimal postpaidRemaining = remaining(postpaidLimit, postpaidUsed);
		BigDecimal totalAvailable = prepaidAvailable == null
			|| postpaidRemaining == null
			? null
			: prepaidAvailable.add(postpaidRemaining);
		return new BillingAmounts(
			prepaidBalance,
			prepaidUsed,
			prepaidAvailable,
			currentCost,
			postpaidLimit,
			postpaidUsed,
			postpaidRemaining,
			totalAvailable
		);
	}

	private synchronized Fetch<List<InvoiceSummary>> historicalInvoices(
		YearMonth period
	) {
		Fetch<List<InvoiceSummary>> fetch = loadHistoricalInvoice(
			period,
			clock.instant()
		);
		if (fetch.loaded()) {
			recentErrorClassification = null;
		}
		return fetch;
	}

	private Fetch<List<InvoiceSummary>> loadHistoricalInvoice(
		YearMonth period,
		Instant now
	) {
		CachedValue<List<InvoiceSummary>> fresh =
			historicalInvoiceCache.getIfPresent(period);
		if (fresh != null) {
			return new Fetch<>(fresh, false, false, null);
		}
		try {
			CachedValue<List<InvoiceSummary>> loaded = new CachedValue<>(
				client.fetchInvoices(period),
				now
			);
			historicalInvoiceCache.put(period, loaded);
			lastHistoricalInvoices.put(period, loaded);
			lastSuccessfulSyncAt = now;
			return new Fetch<>(loaded, false, true, null);
		} catch (RuntimeException exception) {
			XaiManagementFailureType failureType = failureType(exception);
			return new Fetch<>(
				lastHistoricalInvoices.get(period),
				true,
				false,
				failureType
			);
		}
	}

	private <T> Fetch<T> load(
		Cache<String, CachedValue<T>> cache,
		Supplier<T> loader,
		Supplier<CachedValue<T>> lastSuccess,
		Consumer<CachedValue<T>> rememberSuccess,
		Instant now
	) {
		CachedValue<T> fresh = cache.getIfPresent(CACHE_KEY);
		if (fresh != null) {
			return new Fetch<>(fresh, false, false, null);
		}
		try {
			CachedValue<T> loaded = new CachedValue<>(loader.get(), now);
			cache.put(CACHE_KEY, loaded);
			rememberSuccess.accept(loaded);
			lastSuccessfulSyncAt = now;
			return new Fetch<>(loaded, false, true, null);
		} catch (RuntimeException exception) {
			XaiManagementFailureType failureType = failureType(exception);
			return new Fetch<>(lastSuccess.get(), true, false, failureType);
		}
	}

	private AverageCost averageDailyCost(
		CachedValue<InvoicePreview> invoice,
		Instant now
	) {
		try {
			AdminXaiUsageService.CostSummary internal = usageService.costSummary(
				now.minus(7, ChronoUnit.DAYS),
				now
			);
			if (internal.knownCostCalls() > 0) {
				return new AverageCost(
					internal.costUsd().divide(
						BigDecimal.valueOf(7),
						10,
						RoundingMode.HALF_UP
					),
					XaiCostSource.INTERNAL
				);
			}
		} catch (RuntimeException exception) {
			log.warn("Failed to aggregate internal xAI cost; using invoice preview");
		}
		if (invoice == null) {
			return new AverageCost(null, null);
		}
		LocalDate cycleStart = invoice.value().billingCycle().atDay(1);
		LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
		long elapsedDays = ChronoUnit.DAYS.between(cycleStart, today) + 1;
		if (elapsedDays <= 0) {
			return new AverageCost(null, null);
		}
		BigDecimal currentMonthCost = invoice.value().currentMonthCostUsd();
		if (currentMonthCost == null) {
			return new AverageCost(null, null);
		}
		return new AverageCost(
			currentMonthCost
				.divide(
					BigDecimal.valueOf(elapsedDays),
					6,
					RoundingMode.HALF_UP
				),
			XaiCostSource.INVOICE_PREVIEW
		);
	}

	private BillingTotal billedTotal(
		YearMonth firstMonth,
		YearMonth lastMonth
	) {
		BigDecimal total = BigDecimal.ZERO;
		boolean stale = false;
		Fetch<InvoicePreview> preview = null;
		YearMonth currentMonth = YearMonth.from(
			LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
		);
		for (YearMonth month = firstMonth;
			!month.isAfter(lastMonth);
			month = month.plusMonths(1)) {
			if (month.equals(currentMonth)) {
				preview = preview == null ? invoicePreview() : preview;
				stale |= preview.stale();
				if (preview.cachedValue() == null
					|| !preview.cachedValue().value().billingCycle().equals(month)) {
					return new BillingTotal(null, true);
				}
				BigDecimal currentMonthCost = preview.cachedValue()
					.value()
					.currentMonthCostUsd();
				if (currentMonthCost == null) {
					return new BillingTotal(null, stale);
				}
				total = total.add(currentMonthCost);
				continue;
			}
			Fetch<List<InvoiceSummary>> invoices = historicalInvoices(month);
			stale |= invoices.stale();
			if (invoices.failureType() != null) {
				recordFailures(List.of(invoices.failureType()), clock.instant());
			}
			if (invoices.cachedValue() == null) {
				return new BillingTotal(null, true);
			}
			for (InvoiceSummary invoice : invoices.cachedValue().value()) {
				total = total.add(invoice.amountUsd());
			}
		}
		return new BillingTotal(total, stale);
	}

	private synchronized Fetch<InvoicePreview> invoicePreview() {
		Instant now = clock.instant();
		Fetch<InvoicePreview> fetch = load(
			invoiceCache,
			client::fetchInvoicePreview,
			() -> lastInvoice,
			value -> lastInvoice = value,
			now
		);
		if (fetch.failureType() != null) {
			recordFailures(List.of(fetch.failureType()), now);
		} else if (fetch.loaded()) {
			recentErrorClassification = null;
		}
		return fetch;
	}

	private AdminXaiInvoiceResponse invoiceResponse(InvoiceSummary invoice) {
		return new AdminXaiInvoiceResponse(
			invoice.billingPeriod().atDay(1),
			invoice.billingPeriod().atEndOfMonth(),
			invoice.amountUsd(),
			invoice.status()
		);
	}

	private XaiManagementFailureType failureType(RuntimeException exception) {
		return exception instanceof XaiManagementClientException clientException
			? clientException.failureType()
			: XaiManagementFailureType.TEMPORARY_FAILURE;
	}

	private synchronized void recordFailures(
		List<XaiManagementFailureType> failures,
		Instant now
	) {
		lastFailureAt = now;
		recentErrorClassification = failures.contains(
			XaiManagementFailureType.CONFIGURATION_ERROR
		)
			? XaiManagementFailureType.CONFIGURATION_ERROR
			: XaiManagementFailureType.TEMPORARY_FAILURE;
		log.atWarn()
			.addKeyValue("errorClassification", recentErrorClassification)
			.log("xAI Management API query degraded");
	}

	private Instant projectedDepletionAt(
		BigDecimal totalAvailable,
		BigDecimal averageDailyCost,
		Instant now
	) {
		if (totalAvailable == null
			|| averageDailyCost == null
			|| averageDailyCost.signum() <= 0) {
			return null;
		}
		if (totalAvailable.signum() <= 0) {
			return now;
		}
		try {
			long seconds = totalAvailable
				.divide(averageDailyCost, 8, RoundingMode.HALF_UP)
				.multiply(SECONDS_PER_DAY)
				.setScale(0, RoundingMode.DOWN)
				.longValueExact();
			return now.plusSeconds(seconds);
		} catch (ArithmeticException | DateTimeException exception) {
			return null;
		}
	}

	private BigDecimal remaining(BigDecimal limit, BigDecimal used) {
		if (limit == null || used == null) {
			return null;
		}
		return limit.subtract(used).max(BigDecimal.ZERO);
	}

	private <T, R> R value(
		CachedValue<T> cached,
		java.util.function.Function<T, R> mapper
	) {
		return cached == null ? null : mapper.apply(cached.value());
	}

	private void addFailure(
		List<XaiManagementFailureType> failures,
		XaiManagementFailureType failure
	) {
		if (failure != null) {
			failures.add(failure);
		}
	}

	@SafeVarargs
	private final Instant oldestSuccess(Fetch<?>... fetches) {
		Instant oldest = null;
		for (Fetch<?> fetch : fetches) {
			if (fetch.cachedValue() == null) {
				continue;
			}
			Instant fetchedAt = fetch.cachedValue().fetchedAt();
			if (oldest == null || fetchedAt.isBefore(oldest)) {
				oldest = fetchedAt;
			}
		}
		return oldest;
	}

	private <T> Cache<String, CachedValue<T>> cache(
		java.time.Duration ttl,
		Ticker ticker
	) {
		return Caffeine.newBuilder()
			.maximumSize(1)
			.expireAfterWrite(ttl)
			.ticker(ticker)
			.build();
	}

	private record CachedValue<T>(T value, Instant fetchedAt) {
	}

	private record AverageCost(
		BigDecimal amount,
		XaiCostSource source
	) {
	}

	private record BillingAmounts(
		BigDecimal prepaidBalanceUsd,
		BigDecimal prepaidUsedThisPeriodUsd,
		BigDecimal prepaidAvailableUsd,
		BigDecimal currentMonthCostUsd,
		BigDecimal postpaidLimitUsd,
		BigDecimal postpaidUsedUsd,
		BigDecimal postpaidRemainingUsd,
		BigDecimal totalAvailableUsd
	) {
	}

	private record BillingTotal(BigDecimal totalUsd, boolean stale) {
	}

	private record Fetch<T>(
		CachedValue<T> cachedValue,
		boolean stale,
		boolean loaded,
		XaiManagementFailureType failureType
	) {
	}

	private record Snapshot(
		boolean available,
		CachedValue<PrepaidBalance> balance,
		CachedValue<SpendingLimits> limits,
		CachedValue<InvoicePreview> invoice,
		boolean stale,
		Instant fetchedAt,
		Instant lastSuccessfulSyncAt
	) {

		private static Snapshot unavailable() {
			return new Snapshot(
				false,
				null,
				null,
				null,
				false,
				null,
				null
			);
		}
	}
}
