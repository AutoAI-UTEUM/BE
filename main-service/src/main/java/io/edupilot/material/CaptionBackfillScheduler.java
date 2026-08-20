package io.edupilot.material;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CaptionBackfillScheduler {

	private static final Logger log = LoggerFactory.getLogger(
		CaptionBackfillScheduler.class
	);

	private final MaterialCaptionPersistenceService persistenceService;
	private final MaterialCaptionTaskDispatcher dispatcher;
	private final MaterialCaptionProperties properties;

	public CaptionBackfillScheduler(
		MaterialCaptionPersistenceService persistenceService,
		MaterialCaptionTaskDispatcher dispatcher,
		MaterialCaptionProperties properties
	) {
		this.persistenceService = persistenceService;
		this.dispatcher = dispatcher;
		this.properties = properties;
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	public void backfill() {
		List<Long> materialIds = persistenceService.findBackfillCandidates(
			properties.backfillBatch()
		);
		materialIds.forEach(dispatcher::submit);
		if (!materialIds.isEmpty()) {
			log.atInfo()
				.addKeyValue("submitted", materialIds.size())
				.log("Submitted material caption backfill tasks");
		}
	}
}
