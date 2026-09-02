package io.edupilot.admin.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.edupilot.admin.infra.dto.AdminInfraMetricsResponse;
import io.edupilot.admin.infra.dto.AdminInfraMetricsResponse.Latest;
import io.edupilot.admin.infra.dto.AdminInfraMetricsResponse.Point;
import io.edupilot.admin.infra.dto.AdminInfraMetricsResponse.Series;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.ScanBy;

@Service
public class AdminInfraMetricsService {

	private static final Logger log = LoggerFactory.getLogger(
		AdminInfraMetricsService.class
	);
	private static final String INSTANCE_ID = "InstanceId";
	private static final String DISK_QUERY_ID = "disk";
	private static final Pattern INSTANCE_ID_PATTERN = Pattern.compile(
		"^i-[A-Za-z0-9]+$"
	);

	private final AdminInfraProperties properties;
	private final Optional<CloudWatchClient> cloudWatchClient;
	private final Clock clock;
	private final Cache<CacheKey, CachedMetrics> cache;

	public AdminInfraMetricsService(
		AdminInfraProperties properties,
		Optional<CloudWatchClient> cloudWatchClient,
		Clock clock
	) {
		this.properties = properties;
		this.cloudWatchClient = cloudWatchClient;
		this.clock = clock;
		this.cache = Caffeine.newBuilder().maximumSize(8).build();
	}

	public synchronized AdminInfraMetricsResponse metrics(
		String env,
		String range
	) {
		InfraEnvironment environment = InfraEnvironment.parse(env);
		MetricRange metricRange = MetricRange.parse(range);
		if (!properties.enabled()) {
			return AdminInfraMetricsResponse.unavailable("DISABLED");
		}

		String instanceId = instanceId(environment);
		if (instanceId == null || instanceId.isBlank()) {
			return AdminInfraMetricsResponse.unavailable(
				"INSTANCE_NOT_CONFIGURED"
			);
		}
		if (!INSTANCE_ID_PATTERN.matcher(instanceId).matches()) {
			return AdminInfraMetricsResponse.unavailable("INSTANCE_ID_INVALID");
		}

		CacheKey cacheKey = new CacheKey(environment, metricRange);
		Instant now = clock.instant();
		CachedMetrics cached = cache.getIfPresent(cacheKey);
		if (cached != null && cached.isFresh(now, properties.metricsCacheTtl())) {
			return cached.response();
		}

		try {
			CloudWatchClient client = cloudWatchClient.orElseThrow(() ->
				new IllegalStateException("CloudWatch client is unavailable")
			);
			AdminInfraMetricsResponse response = load(
				client,
				environment,
				metricRange,
				instanceId,
				now
			);
			cache.put(cacheKey, new CachedMetrics(response, now));
			return response;
		} catch (RuntimeException exception) {
			String reason = AdminInfraAwsFailure.reason(exception);
			log.atWarn()
				.addKeyValue("env", environment.value)
				.addKeyValue("range", metricRange.value)
				.addKeyValue("reason", reason)
				.addKeyValue(
					"exceptionMessage",
					AdminInfraAwsFailure.safeMessage(exception)
				)
				.log("Admin infrastructure metrics query failed");
			return cached == null
				? AdminInfraMetricsResponse.unavailable(reason)
				: cached.response().asStale(reason);
		}
	}

	private AdminInfraMetricsResponse load(
		CloudWatchClient client,
		InfraEnvironment environment,
		MetricRange metricRange,
		String instanceId,
		Instant to
	) {
		Instant from = to.minus(metricRange.duration);
		GetMetricDataRequest request = GetMetricDataRequest.builder()
			.startTime(from)
			.endTime(to)
			.scanBy(ScanBy.TIMESTAMP_ASCENDING)
			.metricDataQueries(metricQueries(instanceId, metricRange.periodSeconds))
			.build();
		GetMetricDataResponse awsResponse = client.getMetricData(request);
		Map<String, List<Point>> points = mapPoints(awsResponse);
		Series series = new Series(
			points.getOrDefault("cpu", List.of()),
			points.getOrDefault("netIn", List.of()),
			points.getOrDefault("netOut", List.of()),
			points.getOrDefault("mem", List.of()),
			points.getOrDefault("disk", List.of()),
			points.getOrDefault("status", List.of())
		);
		Latest latest = new Latest(
			latest(series.cpu()),
			latest(series.mem()),
			latest(series.disk()),
			latest(series.status())
		);
		return new AdminInfraMetricsResponse(
			true,
			null,
			null,
			environment.value,
			metricRange.value,
			from,
			to,
			metricRange.periodSeconds,
			series,
			latest
		);
	}

	private List<MetricDataQuery> metricQueries(
		String instanceId,
		int periodSeconds
	) {
		return List.of(
			metricQuery(
				"cpu",
				"AWS/EC2",
				"CPUUtilization",
				"Average",
				instanceId,
				periodSeconds
			),
			metricQuery(
				"netIn",
				"AWS/EC2",
				"NetworkIn",
				"Sum",
				instanceId,
				periodSeconds
			),
			metricQuery(
				"netOut",
				"AWS/EC2",
				"NetworkOut",
				"Sum",
				instanceId,
				periodSeconds
			),
			metricQuery(
				"mem",
				"CWAgent",
				"mem_used_percent",
				"Average",
				instanceId,
				periodSeconds
			),
			diskQuery(instanceId, periodSeconds),
			metricQuery(
				"status",
				"AWS/EC2",
				"StatusCheckFailed",
				"Maximum",
				instanceId,
				periodSeconds
			)
		);
	}

	private MetricDataQuery metricQuery(
		String id,
		String namespace,
		String metricName,
		String stat,
		String instanceId,
		int periodSeconds
	) {
		Metric metric = Metric.builder()
			.namespace(namespace)
			.metricName(metricName)
			.dimensions(Dimension.builder()
				.name(INSTANCE_ID)
				.value(instanceId)
				.build())
			.build();
		return MetricDataQuery.builder()
			.id(id)
			.returnData(true)
			.metricStat(MetricStat.builder()
				.metric(metric)
				.period(periodSeconds)
				.stat(stat)
				.build())
			.build();
	}

	private MetricDataQuery diskQuery(String instanceId, int periodSeconds) {
		String expression = String.format(
			Locale.ROOT,
			"SEARCH('{CWAgent,InstanceId,device,fstype,path} "
				+ "MetricName=\"disk_used_percent\" InstanceId=\"%s\" "
				+ "path=\"/\"', 'Maximum', %d)",
			instanceId,
			periodSeconds
		);
		return MetricDataQuery.builder()
			.id(DISK_QUERY_ID)
			.expression(expression)
			.returnData(true)
			.build();
	}

	private Map<String, List<Point>> mapPoints(GetMetricDataResponse response) {
		Map<String, List<Point>> points = new HashMap<>();
		for (MetricDataResult result : response.metricDataResults()) {
			int size = Math.min(result.timestamps().size(), result.values().size());
			List<Point> resultPoints = java.util.stream.IntStream.range(0, size)
				.mapToObj(index -> new Point(
					result.timestamps().get(index),
					result.values().get(index)
				))
				.sorted(Comparator.comparing(Point::t))
				.toList();
			if (DISK_QUERY_ID.equals(result.id())) {
				points.merge(
					DISK_QUERY_ID,
					resultPoints,
					this::mergeMaximumByTimestamp
				);
			} else {
				points.put(result.id(), resultPoints);
			}
		}
		return points;
	}

	private List<Point> mergeMaximumByTimestamp(
		List<Point> first,
		List<Point> second
	) {
		Map<Instant, Double> maximumByTimestamp = new HashMap<>();
		for (Point point : first) {
			maximumByTimestamp.merge(point.t(), point.v(), Double::max);
		}
		for (Point point : second) {
			maximumByTimestamp.merge(point.t(), point.v(), Double::max);
		}
		return maximumByTimestamp.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> new Point(entry.getKey(), entry.getValue()))
			.toList();
	}

	private Double latest(List<Point> points) {
		return points.isEmpty() ? null : points.get(points.size() - 1).v();
	}

	private String instanceId(InfraEnvironment environment) {
		return switch (environment) {
			case PROD -> properties.instances().prod();
			case DEV -> properties.instances().dev();
		};
	}

	private record CacheKey(
		InfraEnvironment environment,
		MetricRange range
	) {
	}

	private record CachedMetrics(
		AdminInfraMetricsResponse response,
		Instant cachedAt
	) {

		private boolean isFresh(Instant now, Duration ttl) {
			return now.isBefore(cachedAt.plus(ttl));
		}
	}

	private enum InfraEnvironment {
		PROD("prod"),
		DEV("dev");

		private final String value;

		InfraEnvironment(String value) {
			this.value = value;
		}

		private static InfraEnvironment parse(String value) {
			for (InfraEnvironment environment : values()) {
				if (environment.value.equals(value)) {
					return environment;
				}
			}
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private enum MetricRange {
		ONE_HOUR("1h", Duration.ofHours(1), 60),
		SIX_HOURS("6h", Duration.ofHours(6), 300),
		TWENTY_FOUR_HOURS("24h", Duration.ofHours(24), 300),
		SEVEN_DAYS("7d", Duration.ofDays(7), 3600);

		private final String value;
		private final Duration duration;
		private final int periodSeconds;

		MetricRange(String value, Duration duration, int periodSeconds) {
			this.value = value;
			this.duration = duration;
			this.periodSeconds = periodSeconds;
		}

		private static MetricRange parse(String value) {
			for (MetricRange range : values()) {
				if (range.value.equals(value)) {
					return range;
				}
			}
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}
}
