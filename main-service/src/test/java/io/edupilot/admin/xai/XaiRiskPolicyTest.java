package io.edupilot.admin.xai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.edupilot.admin.xai.dto.XaiRiskLevel;

class XaiRiskPolicyTest {

	private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
	private final XaiRiskPolicy policy = new XaiRiskPolicy();

	@Test
	void appliesInclusiveSevenAndThirtyDayBoundaries() {
		assertThat(policy.assess(
			new BigDecimal("100"),
			NOW.plus(Duration.ofDays(7)),
			NOW
		)).isEqualTo(XaiRiskLevel.CRITICAL);
		assertThat(policy.assess(
			new BigDecimal("100"),
			NOW.plus(Duration.ofDays(7).plusSeconds(1)),
			NOW
		)).isEqualTo(XaiRiskLevel.WARNING);
		assertThat(policy.assess(
			new BigDecimal("100"),
			NOW.plus(Duration.ofDays(30)),
			NOW
		)).isEqualTo(XaiRiskLevel.WARNING);
		assertThat(policy.assess(
			new BigDecimal("100"),
			NOW.plus(Duration.ofDays(30).plusSeconds(1)),
			NOW
		)).isEqualTo(XaiRiskLevel.NORMAL);
	}

	@Test
	void appliesStrictTenAndFiftyDollarBoundaries() {
		assertThat(policy.assess(new BigDecimal("9.99"), null, NOW))
			.isEqualTo(XaiRiskLevel.CRITICAL);
		assertThat(policy.assess(new BigDecimal("10.00"), null, NOW))
			.isEqualTo(XaiRiskLevel.WARNING);
		assertThat(policy.assess(new BigDecimal("49.99"), null, NOW))
			.isEqualTo(XaiRiskLevel.WARNING);
		assertThat(policy.assess(new BigDecimal("50.00"), null, NOW))
			.isEqualTo(XaiRiskLevel.NORMAL);
	}
}
