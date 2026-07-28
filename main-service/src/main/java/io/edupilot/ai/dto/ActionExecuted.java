package io.edupilot.ai.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ActionExecuted(
	String actionId,
	String agent,
	String status,
	List<Adjustment> adjustments,
	Map<String, Object> artifacts
) {

	public ActionExecuted {
		adjustments = adjustments == null
			? List.of()
			: List.copyOf(adjustments);
		artifacts = artifacts == null
			? Map.of()
			: java.util.Collections.unmodifiableMap(
				new LinkedHashMap<>(artifacts)
			);
	}
}
