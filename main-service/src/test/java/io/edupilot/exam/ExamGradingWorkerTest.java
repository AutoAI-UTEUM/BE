package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExamGradingWorkerTest {

	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

	@Mock private ExamSubmissionPersistenceService persistenceService;
	@Mock private ExamAiGradingService aiGradingService;

	private ExamGradingWorker worker;

	@BeforeEach
	void setUp() {
		worker = new ExamGradingWorker(
			persistenceService,
			aiGradingService,
			new ExamGradingProperties(
				Duration.ofMinutes(5),
				new ExamGradingProperties.Executor(2, 4, 100)
			),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	void appliesResultOnlyAfterClaimingLease() {
		ExamAiGradingOutcome outcome = new ExamAiGradingOutcome(Map.of(), false);
		when(persistenceService.claimGradingLease(eq(10L), any(), eq(NOW), eq(NOW.plusSeconds(300))))
			.thenReturn(true);
		when(aiGradingService.grade(10L)).thenReturn(outcome);

		worker.grade(10L);

		ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
		verify(persistenceService).claimGradingLease(
			eq(10L), token.capture(), eq(NOW), eq(NOW.plusSeconds(300))
		);
		assertThat(token.getValue()).hasSize(36);
		verify(persistenceService).applyAiGrading(10L, token.getValue(), outcome);
	}

	@Test
	void skipsAiWhenAnotherWorkerOwnsLease() {
		when(persistenceService.claimGradingLease(eq(10L), any(), any(), any()))
			.thenReturn(false);

		worker.grade(10L);

		verify(aiGradingService, never()).grade(any());
		verify(persistenceService, never()).applyAiGrading(any(), any(), any());
	}

	@Test
	void marksClaimedSubmissionFailedWhenWorkerThrows() {
		when(persistenceService.claimGradingLease(eq(10L), any(), any(), any()))
			.thenReturn(true);
		when(aiGradingService.grade(10L)).thenThrow(new IllegalStateException("boom"));

		worker.grade(10L);

		ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
		verify(persistenceService).failClaimedGrading(eq(10L), token.capture());
		assertThat(token.getValue()).hasSize(36);
	}
}
