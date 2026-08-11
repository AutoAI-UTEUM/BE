package io.edupilot.exam;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
		int failed = persistenceService.failExhaustedSubmissions(cutoff, now, BATCH_SIZE);
		List<ExamGradingCandidate> requeued = persistenceService.requeueExpiredSubmissions(
			cutoff, now, BATCH_SIZE
		);
		List<ExamGradingCandidate> recoverable = persistenceService.findRecoverableGradings(
			cutoff, now, BATCH_SIZE
		);
		Map<Long, ExamGradingCandidate> candidates = new LinkedHashMap<>();
		requeued.forEach(candidate -> candidates.put(candidate.submissionId(), candidate));
		recoverable.forEach(candidate -> candidates.put(candidate.submissionId(), candidate));
		for (ExamGradingCandidate candidate : candidates.values()) {
			gradingDispatcher.dispatch(candidate.submissionId(), candidate.examId());
		}
		if (failed > 0 || !candidates.isEmpty()) {
			log.atInfo()
				.addKeyValue("failed", failed)
				.addKeyValue("requeued", requeued.size())
				.addKeyValue("redispatched", candidates.size())
				.log("Recovered pending exam grading submissions");
		}
	}
}
