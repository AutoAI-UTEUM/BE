package io.edupilot.exam;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ExamGradingWorker {

	private static final Logger log = LoggerFactory.getLogger(ExamGradingWorker.class);

	private final ExamSubmissionPersistenceService persistenceService;
	private final ExamAiGradingService aiGradingService;
	private final ExamGradingProperties properties;
	private final Clock clock;

	public ExamGradingWorker(
		ExamSubmissionPersistenceService persistenceService,
		ExamAiGradingService aiGradingService,
		ExamGradingProperties properties,
		Clock clock
	) {
		this.persistenceService = persistenceService;
		this.aiGradingService = aiGradingService;
		this.properties = properties;
		this.clock = clock;
	}

	public void grade(Long submissionId) {
		Instant claimedAt = clock.instant();
		String leaseToken = UUID.randomUUID().toString();
		if (!persistenceService.claimGradingLease(
			submissionId,
			leaseToken,
			claimedAt,
			claimedAt.plus(properties.leaseDuration())
		)) {
			return;
		}

		try {
			ExamAiGradingOutcome outcome = aiGradingService.grade(submissionId);
			persistenceService.applyAiGrading(submissionId, leaseToken, outcome);
		} catch (RuntimeException exception) {
			persistenceService.failClaimedGrading(submissionId, leaseToken);
			log.atWarn()
				.addKeyValue("submissionId", submissionId)
				.addKeyValue("failureType", exception.getClass().getSimpleName())
				.log("Exam grading worker failed");
		}
	}
}
