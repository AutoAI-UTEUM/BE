package io.edupilot.admin.xai;

import java.math.BigDecimal;

public record XaiAlertThresholds(
	BigDecimal balanceCriticalUsd,
	BigDecimal balanceWarningUsd,
	int depletionCriticalDays,
	int depletionWarningDays
) {

	public static XaiAlertThresholds defaults() {
		return new XaiAlertThresholds(
			new BigDecimal("10.0000"),
			new BigDecimal("50.0000"),
			7,
			30
		);
	}
}
