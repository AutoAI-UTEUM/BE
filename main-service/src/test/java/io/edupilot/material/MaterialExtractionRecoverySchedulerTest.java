package io.edupilot.material;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class MaterialExtractionRecoverySchedulerTest {

	@Test
	void recoversUsingConfiguredThresholdAndBoundedBatch() {
		MaterialExtractionPersistenceService persistenceService = mock(
			MaterialExtractionPersistenceService.class
		);
		MaterialProperties properties = new MaterialProperties(
			45,
			300,
			Duration.ofMinutes(30)
		);
		Instant now = Instant.parse("2026-08-13T03:00:00Z");
		Instant cutoff = now.minus(Duration.ofMinutes(30));
		when(persistenceService.failStuckExtractions(
			cutoff,
			now,
			MaterialExtractionRecoveryScheduler.BATCH_SIZE
		)).thenReturn(2);
		MaterialExtractionRecoveryScheduler scheduler =
			new MaterialExtractionRecoveryScheduler(
				persistenceService,
				properties,
				Clock.fixed(now, ZoneOffset.UTC)
			);

		scheduler.recover();

		verify(persistenceService).failStuckExtractions(cutoff, now, 100);
	}
}
