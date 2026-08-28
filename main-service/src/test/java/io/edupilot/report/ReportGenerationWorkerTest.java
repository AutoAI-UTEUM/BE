package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.ai.AiClientException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;

@ExtendWith(MockitoExtension.class)
class ReportGenerationWorkerTest {

	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

	@Mock private ReportGenerationPersistenceService persistenceService;
	@Mock private ReportAiGenerationService aiGenerationService;

	private ReportGenerationWorker worker;
	private Logger workerLogger;
	private ListAppender<ILoggingEvent> appender;

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
		workerLogger = (Logger)LoggerFactory.getLogger(ReportGenerationWorker.class);
		appender = new ListAppender<>() {
			@Override
			protected void append(ILoggingEvent event) {
				event.prepareForDeferredProcessing();
				super.append(event);
			}
		};
		appender.start();
		workerLogger.addAppender(appender);
	}

	@AfterEach
	void tearDown() {
		MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
		workerLogger.detachAppender(appender);
		appender.stop();
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
		assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
	}

	@Test
	void successfulGenerationUsesSameUuidTraceIdForAiCallAndLogThenClearsMdc() {
		whenClaimSucceeds();
		ReportAiGenerationService.GeneratedReport generated =
			org.mockito.Mockito.mock(ReportAiGenerationService.GeneratedReport.class);
		AtomicReference<String> traceIdDuringAiCall = new AtomicReference<>();
		when(aiGenerationService.generate(1L)).thenAnswer(invocation -> {
			traceIdDuringAiCall.set(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
			return generated;
		});
		when(persistenceService.applyGeneratedReport(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.same(generated)
		)).thenReturn(true);

		worker.generate(1L);

		String traceId = traceIdDuringAiCall.get();
		assertThat(traceId).isNotNull();
		assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);
		assertThat(appender.list)
			.filteredOn(event -> event.getFormattedMessage().equals(
				"Report generation worker completed"
			))
			.singleElement()
			.satisfies(event -> assertThat(event.getMDCPropertyMap())
				.containsEntry(TraceIdFilter.TRACE_ID_MDC_KEY, traceId));
		assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
	}

	@Test
	void clearsTraceIdWhenLeaseClaimThrows() {
		when(persistenceService.claimGenerationLease(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.eq(NOW),
			org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(300))
		)).thenThrow(new IllegalStateException("lease failure"));

		assertThatThrownBy(() -> worker.generate(1L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("lease failure");
		assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
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
		assertThat(appender.list)
			.filteredOn(event -> event.getFormattedMessage().equals(
				"Report generation worker failed"
			))
			.singleElement()
			.satisfies(event -> {
				String traceId = event.getMDCPropertyMap().get(
					TraceIdFilter.TRACE_ID_MDC_KEY
				);
				assertThat(traceId).isNotNull().isNotEqualTo("unknown");
				assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);
			});
		assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
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
