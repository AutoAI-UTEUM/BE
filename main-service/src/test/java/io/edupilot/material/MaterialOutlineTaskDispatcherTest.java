package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

class MaterialOutlineTaskDispatcherTest {

	@Test
	void executorRejectionIsDeferredToBackfillWithoutAffectingExtraction() {
		Executor rejectingExecutor = command -> {
			throw new RejectedExecutionException("queue full");
		};
		MaterialOutlineGenerationService generationService = mock(
			MaterialOutlineGenerationService.class
		);
		MaterialOutlineTaskDispatcher dispatcher = new MaterialOutlineTaskDispatcher(
			rejectingExecutor,
			generationService
		);

		assertThatCode(() -> dispatcher.submit(10L)).doesNotThrowAnyException();
		verifyNoInteractions(generationService);
	}
}
