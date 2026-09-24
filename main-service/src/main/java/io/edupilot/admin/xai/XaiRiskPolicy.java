package io.edupilot.admin.xai;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Component;

import io.edupilot.admin.xai.dto.XaiRiskLevel;

@Component
public class XaiRiskPolicy {

	public XaiRiskLevel assess(
		BigDecimal totalAvailableUsd,
		Instant projectedDepletionAt,
		Instant now
	) {
		return assess(
			totalAvailableUsd,
			projectedDepletionAt,
			now,
			XaiAlertThresholds.defaults()
		);
	}

	public XaiRiskLevel assess(
		BigDecimal totalAvailableUsd,
		Instant projectedDepletionAt,
		Instant now,
		XaiAlertThresholds thresholds
	) {
		if (totalAvailableUsd.compareTo(thresholds.balanceCriticalUsd()) < 0
			|| within(
				projectedDepletionAt,
				now,
				thresholds.depletionCriticalDays()
			)) {
			return XaiRiskLevel.CRITICAL;
		}
		if (totalAvailableUsd.compareTo(thresholds.balanceWarningUsd()) < 0
			|| within(
				projectedDepletionAt,
				now,
				thresholds.depletionWarningDays()
			)) {
			return XaiRiskLevel.WARNING;
		}
		return XaiRiskLevel.NORMAL;
	}

	private boolean within(
		Instant projectedDepletionAt,
		Instant now,
		int days
	) {
		return projectedDepletionAt != null
			&& !projectedDepletionAt.isAfter(
				now.plus(java.time.Duration.ofDays(days))
			);
	}
}
