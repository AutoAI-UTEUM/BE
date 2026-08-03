package io.edupilot.report;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ReportSnapshotHasher {

	private final ObjectMapper objectMapper;

	public ReportSnapshotHasher() {
		this.objectMapper = new ObjectMapper().findAndRegisterModules();
	}

	public String hash(
		ReportSnapshot.Metrics metrics,
		ReportSnapshot.DataQuality dataQuality,
		List<ReportSnapshot.Evidence> evidence
	) {
		Map<String, Object> quality = new LinkedHashMap<>();
		quality.put("policyVersion", dataQuality.policyVersion());
		quality.put("availableSources", sortedSourceNames(dataQuality.availableSources()));
		quality.put("missingSources", sortedSourceNames(dataQuality.missingSources()));
		quality.put("criterionEligibility", dataQuality.criterionEligibility());

		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("metrics", metrics);
		snapshot.put("dataQuality", quality);
		snapshot.put("evidence", evidence);
		try {
			byte[] canonical = objectMapper.writeValueAsString(canonicalize(snapshot))
				.getBytes(StandardCharsets.UTF_8);
			return java.util.HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(canonical)
			);
		} catch (JsonProcessingException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Cannot hash report snapshot", exception);
		}
	}

	private List<String> sortedSourceNames(Collection<ReportSourceType> sources) {
		return sources.stream()
			.map(Enum::name)
			.sorted()
			.toList();
	}

	private Object canonicalize(Object value) {
		if (value == null || value instanceof String || value instanceof Boolean) {
			return value;
		}
		if (value instanceof BigDecimal decimal) {
			return decimal.stripTrailingZeros().toPlainString();
		}
		if (value instanceof Number) {
			return value;
		}
		if (value instanceof Instant instant) {
			return instant.toString();
		}
		if (value instanceof Enum<?> enumValue) {
			return enumValue.name();
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> sorted = new TreeMap<>();
			map.forEach((key, item) -> sorted.put(key.toString(), canonicalize(item)));
			return sorted;
		}
		if (value instanceof Collection<?> collection) {
			return collection.stream().map(this::canonicalize).toList();
		}
		Map<?, ?> converted = objectMapper.convertValue(value, Map.class);
		return canonicalize(converted);
	}
}
