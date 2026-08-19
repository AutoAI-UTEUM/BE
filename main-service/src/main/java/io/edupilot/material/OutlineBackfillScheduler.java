package io.edupilot.material;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutlineBackfillScheduler {

	private static final Logger log = LoggerFactory.getLogger(
		OutlineBackfillScheduler.class
	);

	private final MaterialOutlinePersistenceService persistenceService;
	private final MaterialOutlineTaskDispatcher dispatcher;
	private final MaterialOutlineProperties properties;

	public OutlineBackfillScheduler(
		MaterialOutlinePersistenceService persistenceService,
		MaterialOutlineTaskDispatcher dispatcher,
		MaterialOutlineProperties properties
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
				.log("Submitted material outline backfill tasks");
		}
	}
}
