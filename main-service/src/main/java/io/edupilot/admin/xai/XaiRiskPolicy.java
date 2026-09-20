package io.edupilot.admin.xai;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import io.edupilot.admin.xai.dto.XaiRiskLevel;

@Component
public class XaiRiskPolicy {

	private static final BigDecimal CRITICAL_BALANCE_USD =
		new BigDecimal("10");
	private static final BigDecimal WARNING_BALANCE_USD =
		new BigDecimal("50");
	private static final Duration CRITICAL_DEPLETION_WINDOW =
		Duration.ofDays(7);
	private static final Duration WARNING_DEPLETION_WINDOW =
		Duration.ofDays(30);

	public XaiRiskLevel assess(
		BigDecimal totalAvailableUsd,
		Instant projectedDepletionAt,
		Instant now
	) {
		if (totalAvailableUsd.compareTo(CRITICAL_BALANCE_USD) < 0
			|| within(projectedDepletionAt, now, CRITICAL_DEPLETION_WINDOW)) {
			return XaiRiskLevel.CRITICAL;
		}
		if (totalAvailableUsd.compareTo(WARNING_BALANCE_USD) < 0
			|| within(projectedDepletionAt, now, WARNING_DEPLETION_WINDOW)) {
			return XaiRiskLevel.WARNING;
		}
		return XaiRiskLevel.NORMAL;
	}

	private boolean within(
		Instant projectedDepletionAt,
		Instant now,
		Duration window
	) {
		return projectedDepletionAt != null
			&& !projectedDepletionAt.isAfter(now.plus(window));
	}
}
