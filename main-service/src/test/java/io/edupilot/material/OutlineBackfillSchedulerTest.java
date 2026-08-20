package io.edupilot.material;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class OutlineBackfillSchedulerTest {

	@Test
	void submitsOnlyBoundedMissingOverviewCandidates() {
		MaterialOutlinePersistenceService persistenceService = mock(
			MaterialOutlinePersistenceService.class
		);
		MaterialOutlineTaskDispatcher dispatcher = mock(
			MaterialOutlineTaskDispatcher.class
		);
		MaterialOutlineProperties properties = new MaterialOutlineProperties(3);
		when(persistenceService.findBackfillCandidates(3))
			.thenReturn(List.of(11L, 12L, 13L));
		OutlineBackfillScheduler scheduler = new OutlineBackfillScheduler(
			persistenceService,
			dispatcher,
			properties
		);

		scheduler.backfill();

		verify(persistenceService).findBackfillCandidates(3);
		verify(dispatcher).submit(11L);
		verify(dispatcher).submit(12L);
		verify(dispatcher).submit(13L);
	}
}
