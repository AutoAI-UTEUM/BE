package io.edupilot.admin.xai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import io.edupilot.admin.xai.XaiManagementClient.PrepaidBalance;
import io.edupilot.admin.xai.XaiManagementClient.SpendingLimits;
import io.edupilot.admin.xai.dto.AdminXaiCreditsResponse;
import io.edupilot.admin.xai.dto.AdminXaiOverviewResponse;
import io.edupilot.admin.xai.dto.AdminXaiStatusResponse;
import io.edupilot.admin.xai.dto.XaiRiskLevel;

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
	private final Clock clock;
	private final Cache<String, CachedValue<PrepaidBalance>> balanceCache;
	private final Cache<String, CachedValue<SpendingLimits>> limitsCache;
	private final Cache<String, CachedValue<InvoicePreview>> invoiceCache;

	private CachedValue<PrepaidBalance> lastBalance;
	private CachedValue<SpendingLimits> lastLimits;
	private CachedValue<InvoicePreview> lastInvoice;
	private Instant lastSuccessfulSyncAt;
	private Instant lastFailureAt;
	private XaiManagementFailureType recentErrorClassification;

	@Autowired
	public AdminXaiService(
		XaiManagementProperties properties,
		XaiManagementClient client,
		XaiRiskPolicy riskPolicy,
		Clock clock
	) {
		this(
			properties,
			client,
			riskPolicy,
			clock,
			Ticker.systemTicker()
		);
	}

	AdminXaiService(
		XaiManagementProperties properties,
		XaiManagementClient client,
		XaiRiskPolicy riskPolicy,
		Clock clock,
		Ticker ticker
	) {
		this.properties = properties;
		this.client = client;
		this.riskPolicy = riskPolicy;
		this.clock = clock;
		this.balanceCache = cache(properties.balanceCacheTtl(), ticker);
		this.limitsCache = cache(properties.limitsCacheTtl(), ticker);
		this.invoiceCache = cache(properties.invoiceCacheTtl(), ticker);
	}

	public AdminXaiCreditsResponse credits() {
		Snapshot snapshot = snapshot(false);
		if (!snapshot.available()) {
			return AdminXaiCreditsResponse.unavailable();
		}
		BigDecimal prepaid = value(snapshot.balance(), PrepaidBalance::balanceUsd);
		BigDecimal limit = value(
			snapshot.limits(),
			SpendingLimits::effectiveLimitUsd
		);
		BigDecimal used = value(
			snapshot.invoice(),
			InvoicePreview::currentMonthCostUsd
		);
		return new AdminXaiCreditsResponse(
			prepaid,
			limit,
			used,
			remaining(limit, used),
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

	private AdminXaiOverviewResponse overview(Snapshot snapshot) {
		if (!snapshot.available()) {
			return AdminXaiOverviewResponse.unavailable();
		}
		Instant now = clock.instant();
		BigDecimal prepaid = value(snapshot.balance(), PrepaidBalance::balanceUsd);
		BigDecimal limit = value(
			snapshot.limits(),
			SpendingLimits::effectiveLimitUsd
		);
		BigDecimal currentCost = value(
			snapshot.invoice(),
			InvoicePreview::currentMonthCostUsd
		);
		BigDecimal postpaidRemaining = remaining(limit, currentCost);
		BigDecimal totalAvailable = prepaid == null || postpaidRemaining == null
			? null
			: prepaid.add(postpaidRemaining);
		BigDecimal averageDailyCost = averageDailyCost(
			snapshot.invoice(),
			now
		);
		Instant projectedDepletionAt = projectedDepletionAt(
			totalAvailable,
			averageDailyCost,
			now
		);
		XaiRiskLevel riskLevel = totalAvailable == null
			? null
			: riskPolicy.assess(
				totalAvailable,
				projectedDepletionAt,
				now
			);
		return new AdminXaiOverviewResponse(
			prepaid,
			currentCost,
			limit,
			postpaidRemaining,
			totalAvailable,
			averageDailyCost,
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
			lastFailureAt = now;
			recentErrorClassification = failures.contains(
				XaiManagementFailureType.CONFIGURATION_ERROR
			)
				? XaiManagementFailureType.CONFIGURATION_ERROR
				: XaiManagementFailureType.TEMPORARY_FAILURE;
			log.atWarn()
				.addKeyValue(
					"errorClassification",
					recentErrorClassification
				)
				.log("xAI Management API query degraded");
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
			XaiManagementFailureType failureType =
				exception instanceof XaiManagementClientException clientException
					? clientException.failureType()
					: XaiManagementFailureType.TEMPORARY_FAILURE;
			return new Fetch<>(lastSuccess.get(), true, false, failureType);
		}
	}

	private BigDecimal averageDailyCost(
		CachedValue<InvoicePreview> invoice,
		Instant now
	) {
		if (invoice == null) {
			return null;
		}
		LocalDate cycleStart = invoice.value().billingCycle().atDay(1);
		LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
		long elapsedDays = ChronoUnit.DAYS.between(cycleStart, today) + 1;
		if (elapsedDays <= 0) {
			return null;
		}
		return invoice.value().currentMonthCostUsd()
			.divide(BigDecimal.valueOf(elapsedDays), 6, RoundingMode.HALF_UP);
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
