package io.edupilot.report;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;

@Component
public class ReportGenerationWorker implements ReportGenerationTask {

	private static final Logger log = LoggerFactory.getLogger(
		ReportGenerationWorker.class
	);

	private final ReportGenerationPersistenceService persistenceService;
	private final ReportAiGenerationService aiGenerationService;
	private final ReportGenerationProperties properties;
	private final Clock clock;

	public ReportGenerationWorker(
		ReportGenerationPersistenceService persistenceService,
		ReportAiGenerationService aiGenerationService,
		ReportGenerationProperties properties,
		Clock clock
	) {
		this.persistenceService = persistenceService;
		this.aiGenerationService = aiGenerationService;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void generate(Long generationId) {
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, UUID.randomUUID().toString());
		try {
			Instant claimedAt = clock.instant();
			String leaseToken = UUID.randomUUID().toString();
			if (!persistenceService.claimGenerationLease(
				generationId,
				leaseToken,
				claimedAt,
				claimedAt.plus(properties.leaseDuration())
			)) {
				return;
			}

			long startedAt = System.nanoTime();
			try {
				ReportAiGenerationService.GeneratedReport generated =
					aiGenerationService.generate(generationId);
				boolean applied = applyWithVersionRetry(
					generationId,
					leaseToken,
					generated
				);
				log.atInfo()
					.addKeyValue("generationId", generationId)
					.addKeyValue("applied", applied)
					.addKeyValue("durationMs", elapsedMillis(startedAt))
					.log("Report generation worker completed");
			} catch (ReportVersionConflictException exception) {
				log.atWarn()
					.addKeyValue("generationId", generationId)
					.addKeyValue("scopeKey", exception.scopeKey())
					.addKeyValue("conflictVersion", exception.version())
					.addKeyValue("durationMs", elapsedMillis(startedAt))
					.log("Report version conflict remained after one apply retry");
			} catch (DataIntegrityViolationException exception) {
				log.atWarn()
					.addKeyValue("generationId", generationId)
					.addKeyValue("durationMs", elapsedMillis(startedAt))
					.log("Discarded concurrent report generation completion");
			} catch (RuntimeException exception) {
				String failureCode = failureCode(exception);
				persistenceService.failClaimedGeneration(
					generationId,
					leaseToken,
					failureCode
				);
				log.atWarn()
					.addKeyValue("generationId", generationId)
					.addKeyValue("failureCode", failureCode)
					.addKeyValue("durationMs", elapsedMillis(startedAt))
					.log("Report generation worker failed");
			}
		} finally {
			MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
		}
	}

	private boolean applyWithVersionRetry(
		Long generationId,
		String leaseToken,
		ReportAiGenerationService.GeneratedReport generated
	) {
		try {
			return persistenceService.applyGeneratedReport(
				generationId, leaseToken, generated
			);
		} catch (ReportVersionConflictException exception) {
			log.atWarn()
				.addKeyValue("generationId", generationId)
				.addKeyValue("scopeKey", exception.scopeKey())
				.addKeyValue("conflictVersion", exception.version())
				.log("Retrying report apply after scope version conflict");
			return persistenceService.applyGeneratedReport(
				generationId, leaseToken, generated
			);
		}
	}

	private String failureCode(RuntimeException exception) {
		if (exception instanceof BusinessException businessException) {
			return businessException.errorCode().code();
		}
		return ErrorCode.INTERNAL_SERVER_ERROR.code();
	}

	private long elapsedMillis(long startedAt) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
	}
}
