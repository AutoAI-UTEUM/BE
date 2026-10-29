package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import io.edupilot.deletion.DeletionJournal;

class MaterialXaiFileLifecycleServiceTest {
	@Test
	void cleanupIsDurablyRecordedWithoutPhysicalProviderCall() {
		var journal=mock(DeletionJournal.class);
		new MaterialXaiFileLifecycleService(journal).deleteAfterCommit("synthetic-file");
		verify(journal).recordExternal("synthetic-file");
	}
	@Test
	void unavailableJournalIsNotReportedAsSuccessfulCleanup() {
		var journal=mock(DeletionJournal.class);
		doThrow(new IllegalStateException("journal unavailable")).when(journal).recordExternal("synthetic-file");
		assertThatThrownBy(()->new MaterialXaiFileLifecycleService(journal).deleteAfterCommit("synthetic-file"))
			.isInstanceOf(IllegalStateException.class);
	}
}