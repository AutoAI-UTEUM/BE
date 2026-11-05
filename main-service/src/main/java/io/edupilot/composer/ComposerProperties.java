package io.edupilot.composer;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** No positive product-policy defaults. Both Spring and AI remain independently OFF. */
@ConfigurationProperties("edupilot.composer")
public record ComposerProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("false") boolean policyConfirmed,
	@DefaultValue("0") int dailyCalls,
	@DefaultValue("0") int callsPerMinute,
	@DefaultValue("0") int concurrentPerUser,
	@DefaultValue("0") long dailyBudgetUsdTicks,
	@DefaultValue("0") long reservationUsdTicks,
	@DefaultValue("10s") Duration timeout
) {
	public boolean ready() {
		return enabled && policyConfirmed && dailyCalls > 0 && dailyCalls <= 1000
			&& callsPerMinute > 0 && callsPerMinute <= dailyCalls
			&& concurrentPerUser > 0 && concurrentPerUser <= 8
			&& reservationUsdTicks > 0 && dailyBudgetUsdTicks >= reservationUsdTicks
			&& timeout != null && timeout.compareTo(Duration.ofMillis(100)) >= 0
			&& timeout.compareTo(Duration.ofSeconds(10)) <= 0;
	}
}
