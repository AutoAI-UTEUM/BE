package io.edupilot.policy.dto;

import java.math.BigDecimal;

import io.edupilot.policy.PolicyType;

public record PolicyConsentStats(
	PolicyType type,
	String version,
	long activeUsers,
	long agreedUsers,
	BigDecimal consentRatePercent
) {
}
