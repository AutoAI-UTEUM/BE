package io.edupilot.auth;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
	prefix = "edupilot.auth.password-reset.cleanup", name = "enabled",
	havingValue = "true", matchIfMissing = true
)
public class PasswordResetCleanupScheduler {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetCleanupScheduler.class);
	private final PasswordResetTokenRepository repository;
	private final Clock clock;

	public PasswordResetCleanupScheduler(PasswordResetTokenRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
	public void cleanup() {
		int deleted = repository.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(7)));
		if (deleted > 0) {
			log.atInfo().addKeyValue("deleted", deleted)
				.log("Expired password reset tokens removed");
		}
	}
}
