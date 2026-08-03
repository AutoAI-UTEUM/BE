package io.edupilot.report;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClientException;
import io.edupilot.global.error.ErrorCode;

@ExtendWith(MockitoExtension.class)
class ReportGenerationWorkerTest {

	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

	@Mock private ReportGenerationPersistenceService persistenceService;
	@Mock private ReportAiGenerationService aiGenerationService;

	private ReportGenerationWorker worker;

	@BeforeEach
	void setUp() {
		ReportGenerationProperties properties = new ReportGenerationProperties(
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			new ReportGenerationProperties.Executor(1, 2, 50)
		);
		worker = new ReportGenerationWorker(
			persistenceService,
			aiGenerationService,
			properties,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	void doesNothingWhenLeaseClaimFails() {
		when(persistenceService.claimGenerationLease(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.eq(NOW),
			org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(300))
		)).thenReturn(false);

		worker.generate(1L);

		verify(aiGenerationService, never()).generate(1L);
	}

	@Test
	void timeoutMarksClaimedGenerationFailedWithTimeoutCode() {
		when(persistenceService.claimGenerationLease(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.eq(NOW),
			org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(300))
		)).thenReturn(true);
		when(aiGenerationService.generate(1L)).thenThrow(
			new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT)
		);

		worker.generate(1L);

		ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
		verify(persistenceService).failClaimedGeneration(
			org.mockito.ArgumentMatchers.eq(1L),
			token.capture(),
			org.mockito.ArgumentMatchers.eq("AI_SERVICE_TIMEOUT")
		);
	}

	@Test
	void invalidResponseMarksClaimedGenerationFailedWithoutApplying() {
		when(persistenceService.claimGenerationLease(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.eq(NOW),
			org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(300))
		)).thenReturn(true);
		when(aiGenerationService.generate(1L)).thenThrow(
			new AiClientException(ErrorCode.AI_RESPONSE_INVALID)
		);

		worker.generate(1L);

		verify(persistenceService).failClaimedGeneration(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.eq("AI_RESPONSE_INVALID")
		);
		verify(persistenceService, never()).applyGeneratedReport(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}
}
