package io.edupilot.report;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportGenerationRecoveryScheduler {

	static final int BATCH_SIZE = 100;

	private static final Logger log = LoggerFactory.getLogger(
		ReportGenerationRecoveryScheduler.class
	);

	private final ReportGenerationPersistenceService persistenceService;
	private final ReportGenerationDispatcher dispatcher;
	private final ReportGenerationProperties properties;
	private final Clock clock;

	public ReportGenerationRecoveryScheduler(
		ReportGenerationPersistenceService persistenceService,
		ReportGenerationDispatcher dispatcher,
		ReportGenerationProperties properties,
		Clock clock
	) {
		this.persistenceService = persistenceService;
		this.dispatcher = dispatcher;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
	public void recover() {
		Instant now = clock.instant();
		Instant cutoff = now.minus(properties.cutoff());
		int expired = persistenceService.failExpiredGenerations(
			cutoff,
			now,
			BATCH_SIZE
		);
		List<Long> recoverable = persistenceService.findRecoverableGenerations(
			cutoff,
			now,
			BATCH_SIZE
		);
		recoverable.forEach(dispatcher::dispatch);
		if (expired > 0 || !recoverable.isEmpty()) {
			log.atInfo()
				.addKeyValue("expired", expired)
				.addKeyValue("redispatched", recoverable.size())
				.log("Recovered pending report generations");
		}
	}
}
