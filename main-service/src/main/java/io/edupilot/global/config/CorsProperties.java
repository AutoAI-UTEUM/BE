package io.edupilot.global.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edupilot.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		if (allowedOrigins == null || allowedOrigins.isEmpty()) {
			throw new IllegalArgumentException("At least one CORS origin is required.");
		}

		allowedOrigins = allowedOrigins.stream()
			.map(String::trim)
			.filter(origin -> !origin.isEmpty())
			.toList();

		if (allowedOrigins.isEmpty() || allowedOrigins.contains("*")) {
			throw new IllegalArgumentException("CORS origins must be explicit and cannot use '*'.");
		}
	}
}
