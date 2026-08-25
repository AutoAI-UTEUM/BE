package io.edupilot.exam;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
class ExamGradingRecoverySchedulerTest {

	@Test
	void retriesCutoffThenFailsExhaustedAndDeduplicatesDispatch() {
		ExamSubmissionPersistenceService persistenceService = mock(
			ExamSubmissionPersistenceService.class
		);
		ExamGradingDispatcher dispatcher = mock(ExamGradingDispatcher.class);
		Instant now = Instant.parse("2026-08-03T02:00:00Z");
		Instant cutoff = now.minus(ExamGradingRecoveryScheduler.GRADING_CUTOFF);
		Clock clock = Clock.fixed(now, ZoneOffset.UTC);
		when(persistenceService.failExhaustedSubmissions(
			cutoff, now, ExamGradingRecoveryScheduler.BATCH_SIZE
		)).thenReturn(2);
		when(persistenceService.requeueExpiredSubmissions(
			cutoff, now, ExamGradingRecoveryScheduler.BATCH_SIZE
		)).thenReturn(List.of(
			new ExamGradingCandidate(10L, 20L),
			new ExamGradingCandidate(11L, 21L)
		));
		when(persistenceService.findRecoverableGradings(
			cutoff, now, ExamGradingRecoveryScheduler.BATCH_SIZE
		)).thenReturn(List.of(
			new ExamGradingCandidate(11L, 21L),
			new ExamGradingCandidate(12L, 22L)
		));
		ExamGradingRecoveryScheduler scheduler = new ExamGradingRecoveryScheduler(
			persistenceService, dispatcher, clock
		);

		scheduler.recover();

		verify(persistenceService).failExhaustedSubmissions(
			cutoff, now, 100
		);
		verify(persistenceService).requeueExpiredSubmissions(cutoff, now, 100);
		verify(persistenceService).findRecoverableGradings(
			cutoff, now, 100
		);
		verify(dispatcher).dispatch(10L, 20L);
		verify(dispatcher, times(1)).dispatch(11L, 21L);
		verify(dispatcher).dispatch(12L, 22L);
	}
}
