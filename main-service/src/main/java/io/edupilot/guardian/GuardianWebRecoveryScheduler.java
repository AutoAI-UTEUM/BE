package io.edupilot.guardian;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovery records uncertainty. It never repeats an SMS send or approves an account. */
@Component
public class GuardianWebRecoveryScheduler {
	private final GuardianWebPersistence persistence;
	public GuardianWebRecoveryScheduler(GuardianWebPersistence persistence) { this.persistence = persistence; }
	@Scheduled(fixedDelayString = "${edupilot.guardian.web.recovery-delay-ms:60000}",
		initialDelayString = "${edupilot.guardian.web.recovery-initial-delay-ms:60000}")
	public void recover() { persistence.expiredIds().forEach(persistence::expire); }
}
