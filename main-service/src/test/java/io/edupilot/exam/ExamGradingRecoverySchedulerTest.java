package io.edupilot.exam;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ExamGradingRecoverySchedulerTest {

	@Test
	void expiresCutoffBeforeRedispatchingAtMostOneBatch() {
		ExamSubmissionPersistenceService persistenceService = mock(
			ExamSubmissionPersistenceService.class
		);
		ExamGradingDispatcher dispatcher = mock(ExamGradingDispatcher.class);
		Instant now = Instant.parse("2026-08-03T02:00:00Z");
		Instant cutoff = now.minus(ExamGradingRecoveryScheduler.GRADING_CUTOFF);
		Clock clock = Clock.fixed(now, ZoneOffset.UTC);
		when(persistenceService.failExpiredSubmissions(
			cutoff, now, ExamGradingRecoveryScheduler.BATCH_SIZE
		)).thenReturn(2);
		when(persistenceService.findRecoverableGradings(
			cutoff, now, ExamGradingRecoveryScheduler.BATCH_SIZE
		)).thenReturn(List.of(
			new ExamGradingCandidate(10L, 20L),
			new ExamGradingCandidate(11L, 21L)
		));
		ExamGradingRecoveryScheduler scheduler = new ExamGradingRecoveryScheduler(
			persistenceService, dispatcher, clock
		);

		scheduler.recover();

		InOrder order = inOrder(persistenceService, dispatcher);
		order.verify(persistenceService).failExpiredSubmissions(
			cutoff, now, 100
		);
		order.verify(persistenceService).findRecoverableGradings(
			cutoff, now, 100
		);
		order.verify(dispatcher).dispatch(10L, 20L);
		order.verify(dispatcher).dispatch(11L, 21L);
	}
}
