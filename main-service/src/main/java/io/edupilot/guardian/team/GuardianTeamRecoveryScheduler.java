package io.edupilot.guardian.team;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded restart recovery. Expiry also remains enforceable in every decision and access check. */
@Component
public class GuardianTeamRecoveryScheduler {
	private static final Logger log = LoggerFactory.getLogger(GuardianTeamRecoveryScheduler.class);
	private final GuardianTeamService service;
	public GuardianTeamRecoveryScheduler(GuardianTeamService service) { this.service = service; }
	@Scheduled(fixedDelayString = "${edupilot.guardian.team.recovery-interval-ms:30000}",
		initialDelayString = "${edupilot.guardian.team.recovery-initial-delay-ms:30000}")
	public void recover() {
		for (String id : service.recoveryIds()) {
			try { service.recover(id); }
			catch (RuntimeException failure) { log.warn("guardian_team_recovery_failed category={}", failure.getClass().getSimpleName()); }
		}
	}
}
