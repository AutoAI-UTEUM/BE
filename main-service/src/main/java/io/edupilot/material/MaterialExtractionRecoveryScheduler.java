package io.edupilot.material;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaterialExtractionRecoveryScheduler {

	static final int BATCH_SIZE = 100;

	private static final Logger log = LoggerFactory.getLogger(
		MaterialExtractionRecoveryScheduler.class
	);

	private final MaterialExtractionPersistenceService persistenceService;
	private final MaterialProperties properties;
	private final Clock clock;

	public MaterialExtractionRecoveryScheduler(
		MaterialExtractionPersistenceService persistenceService,
		MaterialProperties properties,
		Clock clock
	) {
		this.persistenceService = persistenceService;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
	public void recover() {
		Instant now = clock.instant();
		Instant cutoff = now.minus(properties.extractionStuckThreshold());
		int failed = persistenceService.failStuckExtractions(
			cutoff,
			now,
			BATCH_SIZE
		);
		if (failed > 0) {
			log.atInfo()
				.addKeyValue("failed", failed)
				.log("Recovered stuck material extractions");
		}
	}
}
