package io.edupilot.material;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class XaiFileBackfillSchedulerTest {

	@Test
	void disabledKillSwitchDoesNotQueryOrSubmit() {
		MaterialXaiFileBackfillPersistenceService persistenceService =
			org.mockito.Mockito.mock(MaterialXaiFileBackfillPersistenceService.class);
		MaterialXaiFileBackfillTaskDispatcher dispatcher =
			org.mockito.Mockito.mock(MaterialXaiFileBackfillTaskDispatcher.class);
		XaiFileBackfillScheduler scheduler = new XaiFileBackfillScheduler(
			persistenceService,
			dispatcher,
			new MaterialXaiFileBackfillProperties(
				false,
				1,
				Duration.ofHours(6)
			)
		);

		scheduler.backfill();

		verifyNoInteractions(persistenceService, dispatcher);
	}

	@Test
	void enabledKillSwitchSubmitsBoundedCandidates() {
		MaterialXaiFileBackfillPersistenceService persistenceService =
			org.mockito.Mockito.mock(MaterialXaiFileBackfillPersistenceService.class);
		MaterialXaiFileBackfillTaskDispatcher dispatcher =
			org.mockito.Mockito.mock(MaterialXaiFileBackfillTaskDispatcher.class);
		when(persistenceService.findCandidates()).thenReturn(List.of(10L));
		XaiFileBackfillScheduler scheduler = new XaiFileBackfillScheduler(
			persistenceService,
			dispatcher,
			new MaterialXaiFileBackfillProperties(
				true,
				1,
				Duration.ofHours(6)
			)
		);

		scheduler.backfill();

		verify(persistenceService).findCandidates();
		verify(dispatcher).submit(10L);
		verify(dispatcher, never()).submit(11L);
	}
}
