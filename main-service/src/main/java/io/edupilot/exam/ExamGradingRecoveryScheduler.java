package io.edupilot.exam;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExamGradingRecoveryScheduler {

	static final Duration GRADING_CUTOFF = Duration.ofMinutes(30);
	static final int BATCH_SIZE = 100;

	private static final Logger log = LoggerFactory.getLogger(ExamGradingRecoveryScheduler.class);

	private final ExamSubmissionPersistenceService persistenceService;
	private final ExamGradingDispatcher gradingDispatcher;
	private final Clock clock;

	public ExamGradingRecoveryScheduler(
		ExamSubmissionPersistenceService persistenceService,
		ExamGradingDispatcher gradingDispatcher,
		Clock clock
	) {
		this.persistenceService = persistenceService;
		this.gradingDispatcher = gradingDispatcher;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
	public void recover() {
		Instant now = clock.instant();
		Instant cutoff = now.minus(GRADING_CUTOFF);
		int expired = persistenceService.failExpiredSubmissions(cutoff, now, BATCH_SIZE);
		List<ExamGradingCandidate> recoverable = persistenceService.findRecoverableGradings(
			cutoff, now, BATCH_SIZE
		);
		for (ExamGradingCandidate candidate : recoverable) {
			gradingDispatcher.dispatch(candidate.submissionId(), candidate.examId());
		}
		if (expired > 0 || !recoverable.isEmpty()) {
			log.atInfo()
				.addKeyValue("expired", expired)
				.addKeyValue("redispatched", recoverable.size())
				.log("Recovered pending exam grading submissions");
		}
	}
}
