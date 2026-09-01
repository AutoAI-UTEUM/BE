package io.edupilot.admin.infra;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.edupilot.admin.infra.dto.AdminInfraCostResponse;
import io.edupilot.admin.infra.dto.AdminInfraCostResponse.DailyCost;
import io.edupilot.admin.infra.dto.AdminInfraCostResponse.MonthToDate;
import io.edupilot.admin.infra.dto.AdminInfraCostResponse.ServiceCost;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.Group;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinitionType;
import software.amazon.awssdk.services.costexplorer.model.MetricValue;
import software.amazon.awssdk.services.costexplorer.model.ResultByTime;

@Service
public class AdminInfraCostService {

	private static final Logger log = LoggerFactory.getLogger(
		AdminInfraCostService.class
	);
	private static final String COST_METRIC = "UnblendedCost";
	private static final String DEFAULT_CURRENCY = "USD";
	private static final String COST_NOTE =
		"Cost Explorer data is finalized through yesterday.";

	private final AdminInfraProperties properties;
	private final Optional<CostExplorerClient> costExplorerClient;
	private final Clock clock;
	private final Cache<String, CachedCost> cache;

	public AdminInfraCostService(
		AdminInfraProperties properties,
		Optional<CostExplorerClient> costExplorerClient,
		Clock clock
	) {
		this.properties = properties;
		this.costExplorerClient = costExplorerClient;
		this.clock = clock;
		this.cache = Caffeine.newBuilder().maximumSize(1).build();
	}

	public synchronized AdminInfraCostResponse cost() {
		if (!properties.enabled()) {
			return AdminInfraCostResponse.unavailable("DISABLED");
		}

		Instant now = clock.instant();
		CachedCost cached = cache.getIfPresent(COST_METRIC);
		if (cached != null && cached.isFresh(now, properties.costCacheTtl())) {
			return cached.response();
		}

		try {
			CostExplorerClient client = costExplorerClient.orElseThrow(() ->
				new IllegalStateException("Cost Explorer client is unavailable")
			);
			AdminInfraCostResponse response = load(client, now);
			cache.put(COST_METRIC, new CachedCost(response, now));
			return response;
		} catch (RuntimeException exception) {
			String reason = AdminInfraAwsFailure.reason(exception);
			log.atWarn()
				.addKeyValue("reason", reason)
				.addKeyValue(
					"exceptionMessage",
					AdminInfraAwsFailure.safeMessage(exception)
				)
				.log("Admin infrastructure cost query failed");
			return cached == null
				? AdminInfraCostResponse.unavailable(reason)
				: cached.response().asStale(reason);
		}
	}

	private AdminInfraCostResponse load(
		CostExplorerClient client,
		Instant updatedAt
	) {
		LocalDate today = LocalDate.ofInstant(updatedAt, ZoneOffset.UTC);
		LocalDate monthStart = today.withDayOfMonth(1);
		LocalDate recentStart = today.minusDays(29);
		LocalDate queryStart = monthStart.isBefore(recentStart)
			? monthStart
			: recentStart;
		GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
			.timePeriod(DateInterval.builder()
				.start(queryStart.toString())
				.end(today.plusDays(1).toString())
				.build())
			.granularity(Granularity.DAILY)
			.metrics(COST_METRIC)
			.groupBy(GroupDefinition.builder()
				.type(GroupDefinitionType.DIMENSION)
				.key("SERVICE")
				.build())
			.build();

		GetCostAndUsageResponse awsResponse = client.getCostAndUsage(request);
		Map<String, BigDecimal> monthByService = new HashMap<>();
		List<DailyCost> daily = new ArrayList<>();
		String currency = DEFAULT_CURRENCY;
		for (ResultByTime result : awsResponse.resultsByTime()) {
			LocalDate date = LocalDate.parse(result.timePeriod().start());
			BigDecimal dailyTotal = BigDecimal.ZERO;
			for (Group group : result.groups()) {
				MetricValue metric = group.metrics().get(COST_METRIC);
				if (metric == null || metric.amount() == null) {
					continue;
				}
				BigDecimal amount = new BigDecimal(metric.amount());
				dailyTotal = dailyTotal.add(amount);
				if (metric.unit() != null && !metric.unit().isBlank()) {
					currency = metric.unit();
				}
				if (!date.isBefore(monthStart)) {
					String service = group.keys().isEmpty()
						? "Unknown"
						: group.keys().get(0);
					monthByService.merge(service, amount, BigDecimal::add);
				}
			}
			if (!date.isBefore(recentStart) && !date.isAfter(today)) {
				daily.add(new DailyCost(date, dailyTotal));
			}
		}
		daily.sort(Comparator.comparing(DailyCost::date));

		List<Map.Entry<String, BigDecimal>> ranked = monthByService.entrySet()
			.stream()
			.sorted(Map.Entry.<String, BigDecimal>comparingByValue()
				.reversed()
				.thenComparing(Map.Entry.comparingByKey()))
			.toList();
		List<ServiceCost> byService = new ArrayList<>();
		for (int index = 0; index < Math.min(10, ranked.size()); index++) {
			Map.Entry<String, BigDecimal> entry = ranked.get(index);
			byService.add(new ServiceCost(entry.getKey(), entry.getValue()));
		}
		if (ranked.size() > 10) {
			BigDecimal other = ranked.subList(10, ranked.size()).stream()
				.map(Map.Entry::getValue)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			byService.add(new ServiceCost("Other", other));
		}
		BigDecimal monthTotal = monthByService.values().stream()
			.reduce(BigDecimal.ZERO, BigDecimal::add);

		return new AdminInfraCostResponse(
			true,
			null,
			null,
			currency,
			new MonthToDate(monthTotal, List.copyOf(byService)),
			List.copyOf(daily),
			updatedAt,
			COST_NOTE
		);
	}

	private record CachedCost(
		AdminInfraCostResponse response,
		Instant cachedAt
	) {

		private boolean isFresh(Instant now, Duration ttl) {
			return now.isBefore(cachedAt.plus(ttl));
		}
	}
}
