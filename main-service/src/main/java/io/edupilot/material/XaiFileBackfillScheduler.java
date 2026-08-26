package io.edupilot.material;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class XaiFileBackfillScheduler {

	private static final Logger log = LoggerFactory.getLogger(
		XaiFileBackfillScheduler.class
	);

	private final MaterialXaiFileBackfillPersistenceService persistenceService;
	private final MaterialXaiFileBackfillTaskDispatcher dispatcher;
	private final MaterialXaiFileBackfillProperties properties;

	public XaiFileBackfillScheduler(
		MaterialXaiFileBackfillPersistenceService persistenceService,
		MaterialXaiFileBackfillTaskDispatcher dispatcher,
		MaterialXaiFileBackfillProperties properties
	) {
		this.persistenceService = persistenceService;
		this.dispatcher = dispatcher;
		this.properties = properties;
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	public void backfill() {
		if (!properties.enabled()) {
			return;
		}
		List<Long> materialIds = persistenceService.findCandidates();
		materialIds.forEach(dispatcher::submit);
		if (!materialIds.isEmpty()) {
			log.atInfo()
				.addKeyValue("submitted", materialIds.size())
				.log("Submitted material xAI file backfill tasks");
		}
	}
}
