package io.edupilot.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationScheduler {

	static final int BATCH_SIZE = 100;
	static final Duration RETENTION = Duration.ofDays(30);

	private static final Logger log = LoggerFactory.getLogger(
		NotificationScheduler.class
	);

	private final NotificationTriggerService triggerService;
	private final Clock clock;

	public NotificationScheduler(
		NotificationTriggerService triggerService,
		Clock clock
	) {
		this.triggerService = triggerService;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
	public void publishScheduledNotices() {
		int published = triggerService.publishDueNotices(
			clock.instant(),
			BATCH_SIZE
		);
		if (published > 0) {
			log.atInfo()
				.addKeyValue("published", published)
				.log("Published scheduled notice notifications");
		}
	}

	@Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
	public void deleteExpiredNotifications() {
		Instant cutoff = clock.instant().minus(RETENTION);
		int deleted = triggerService.deleteExpired(cutoff, BATCH_SIZE);
		if (deleted > 0) {
			log.atInfo()
				.addKeyValue("deleted", deleted)
				.log("Deleted expired notifications");
		}
	}
}
