package io.edupilot.exam;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
	prefix = "edupilot.exam.draft.cleanup", name = "enabled",
	havingValue = "true", matchIfMissing = true
)
public class ExamAttemptDraftCleanupScheduler {

	private static final Logger log = LoggerFactory.getLogger(
		ExamAttemptDraftCleanupScheduler.class
	);
	private final ExamAttemptDraftRepository repository;
	private final Clock clock;

	public ExamAttemptDraftCleanupScheduler(ExamAttemptDraftRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
	public void cleanup() {
		int deleted = repository.deleteUpdatedBefore(
			clock.instant().minus(Duration.ofDays(30))
		);
		if (deleted > 0) {
			log.atInfo().addKeyValue("deleted", deleted)
				.log("Stale exam attempt drafts removed");
		}
	}
}
