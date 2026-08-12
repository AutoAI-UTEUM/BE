package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.dao.DataIntegrityViolationException;

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
			5,
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

	@Test
	void retriesOnlyTheApplyTransactionOnceAfterScopeVersionConflict() {
		whenClaimSucceeds();
		ReportAiGenerationService.GeneratedReport generated =
			org.mockito.Mockito.mock(ReportAiGenerationService.GeneratedReport.class);
		when(aiGenerationService.generate(1L)).thenReturn(generated);
		when(persistenceService.applyGeneratedReport(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.same(generated)
		)).thenThrow(versionConflict(2)).thenReturn(true);

		worker.generate(1L);

		verify(aiGenerationService).generate(1L);
		verify(persistenceService, times(2)).applyGeneratedReport(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.same(generated)
		);
		verifyNoFailureTransition();
	}

	@Test
	void secondScopeVersionConflictKeepsExistingLeaseRecoveryPath() {
		whenClaimSucceeds();
		ReportAiGenerationService.GeneratedReport generated =
			org.mockito.Mockito.mock(ReportAiGenerationService.GeneratedReport.class);
		when(aiGenerationService.generate(1L)).thenReturn(generated);
		when(persistenceService.applyGeneratedReport(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.same(generated)
		)).thenThrow(versionConflict(2), versionConflict(3));

		worker.generate(1L);

		verify(aiGenerationService).generate(1L);
		verify(persistenceService, times(2)).applyGeneratedReport(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.same(generated)
		);
		verifyNoFailureTransition();
	}

	@Test
	void recognizesOnlyTheScopeVersionUniqueConstraintAsRetryable() {
		DataIntegrityViolationException scopeConflict =
			new DataIntegrityViolationException(
				"Duplicate entry for key '"
					+ ReportVersionConflictException.CONSTRAINT_NAME + "'"
			);
		DataIntegrityViolationException anotherConstraint =
			new DataIntegrityViolationException(
				"Duplicate entry for key 'uk_student_reports_generation'"
			);

		assertThat(ReportVersionConflictException.matches(scopeConflict)).isTrue();
		assertThat(ReportVersionConflictException.matches(anotherConstraint)).isFalse();
	}

	private void whenClaimSucceeds() {
		when(persistenceService.claimGenerationLease(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.eq(NOW),
			org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(300))
		)).thenReturn(true);
	}

	private void verifyNoFailureTransition() {
		verify(persistenceService, never()).failClaimedGeneration(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	private ReportVersionConflictException versionConflict(int version) {
		return new ReportVersionConflictException(
			"FULL",
			version,
			new DataIntegrityViolationException("scope version conflict")
		);
	}
}
