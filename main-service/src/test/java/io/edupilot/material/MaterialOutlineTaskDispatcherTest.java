package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

class MaterialOutlineTaskDispatcherTest {

	@Test
	void executorRejectionIsDeferredToBackfillWithoutAffectingExtraction() {
		Executor rejectingExecutor = command -> {
			throw new RejectedExecutionException("queue full");
		};
		MaterialOutlineGenerationService generationService = mock(
			MaterialOutlineGenerationService.class
		);
		MaterialOutlinePersistenceService persistenceService = mock(
			MaterialOutlinePersistenceService.class
		);
		MaterialOutlineTaskDispatcher dispatcher = new MaterialOutlineTaskDispatcher(
			rejectingExecutor,
			generationService,
			persistenceService
		);

		assertThatCode(() -> dispatcher.submit(10L)).doesNotThrowAnyException();
		verify(persistenceService).markFailed(10L);
		verifyNoInteractions(generationService);
	}

	@Test
	void manualSubmissionQueuesWorkWithoutRunningAiOnCallerThread() {
		AtomicReference<Runnable> queued = new AtomicReference<>();
		MaterialOutlineGenerationService generationService = mock(
			MaterialOutlineGenerationService.class
		);
		MaterialOutlineTaskDispatcher dispatcher = new MaterialOutlineTaskDispatcher(
			queued::set, generationService,
			mock(MaterialOutlinePersistenceService.class)
		);

		dispatcher.submitManual(10L);

		verifyNoInteractions(generationService);
		queued.get().run();
		verify(generationService).generateManual(10L);
	}

	@Test
	void rejectedManualSubmissionIsNotReportedAsAccepted() {
		MaterialOutlinePersistenceService persistenceService = mock(
			MaterialOutlinePersistenceService.class
		);
		MaterialOutlineTaskDispatcher dispatcher = new MaterialOutlineTaskDispatcher(
			command -> { throw new RejectedExecutionException(); },
			mock(MaterialOutlineGenerationService.class), persistenceService
		);

		assertThatThrownBy(() -> dispatcher.submitManual(10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				org.assertj.core.api.Assertions.assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE));
		verify(persistenceService).markFailed(10L);
	}
}
