package io.edupilot.material;

import static org.mockito.Mockito.verify;

import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

@ExtendWith(MockitoExtension.class)
class MaterialExtractionEventListenerTest {

	@Mock
	private MaterialExtractionService extractionService;

	@Mock
	private MaterialExtractionPersistenceService persistenceService;

	@Test
	void acceptedTaskRunsExtractionWithCapturedTraceId() {
		Executor directExecutor = Runnable::run;
		MaterialExtractionEventListener listener = new MaterialExtractionEventListener(
			directExecutor,
			extractionService,
			persistenceService
		);

		listener.onExtractionRequested(
			new MaterialExtractionRequested(10L, "trace-10")
		);

		verify(extractionService).extract(10L, "trace-10");
	}

	@Test
	void rejectedTaskMarksMaterialFailed() {
		Executor rejectingExecutor = task -> {
			throw new TaskRejectedException("full");
		};
		MaterialExtractionEventListener listener = new MaterialExtractionEventListener(
			rejectingExecutor,
			extractionService,
			persistenceService
		);

		listener.onExtractionRequested(
			new MaterialExtractionRequested(10L, "trace-10")
		);

		verify(persistenceService).fail(10L);
	}
}
