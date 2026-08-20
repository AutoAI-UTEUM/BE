package io.edupilot.material;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class CaptionBackfillSchedulerTest {

	@Test
	void submitsOnlyConfiguredMissingCaptionCandidates() {
		MaterialCaptionPersistenceService persistenceService = mock(
			MaterialCaptionPersistenceService.class
		);
		MaterialCaptionTaskDispatcher dispatcher = mock(
			MaterialCaptionTaskDispatcher.class
		);
		when(persistenceService.findBackfillCandidates(1)).thenReturn(List.of(11L));
		CaptionBackfillScheduler scheduler = new CaptionBackfillScheduler(
			persistenceService,
			dispatcher,
			new MaterialCaptionProperties(1)
		);

		scheduler.backfill();

		verify(persistenceService).findBackfillCandidates(1);
		verify(dispatcher).submit(11L);
	}
}
