package io.edupilot.notification;

import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NotificationSchedulerTest {

	@Test
	void scansScheduledNoticesAndDeletesOnlyOlderThanThirtyDaysInBatches() {
		NotificationTriggerService triggerService = Mockito.mock(
			NotificationTriggerService.class
		);
		Instant now = Instant.parse("2026-08-14T03:00:00Z");
		NotificationScheduler scheduler = new NotificationScheduler(
			triggerService,
			Clock.fixed(now, ZoneOffset.UTC)
		);

		scheduler.publishScheduledNotices();
		scheduler.deleteExpiredNotifications();

		verify(triggerService).publishDueNotices(now, 100);
		verify(triggerService).deleteExpired(
			now.minusSeconds(30L * 24 * 60 * 60),
			100
		);
	}
}
