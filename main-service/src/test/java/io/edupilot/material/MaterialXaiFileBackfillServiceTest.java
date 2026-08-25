package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import io.edupilot.ai.AiClient;
import io.edupilot.material.MaterialXaiFileBackfillPersistenceService.UploadClaim;
import io.edupilot.material.storage.FileStorage;

@ExtendWith(MockitoExtension.class)
class MaterialXaiFileBackfillServiceTest {

	@Mock
	private MaterialXaiFileBackfillPersistenceService persistenceService;

	@Mock
	private FileStorage fileStorage;

	@Mock
	private AiClient aiClient;

	@Mock
	private MaterialXaiFileLifecycleService lifecycleService;

	@Test
	void uploadsOutsidePersistenceAndAttachesReturnedFileId() {
		ByteArrayResource resource = new ByteArrayResource("%PDF-test".getBytes());
		when(persistenceService.claim(10L))
			.thenReturn(Optional.of(new UploadClaim(10L, "private-key")));
		when(fileStorage.load("private-key")).thenReturn(resource);
		when(aiClient.uploadFile(resource)).thenReturn("file-new");
		when(persistenceService.attachIfStillEligible(10L, "file-new"))
			.thenReturn(true);

		service().backfill(10L);

		verify(persistenceService).attachIfStillEligible(10L, "file-new");
		verify(lifecycleService, never()).deleteAfterCommit("file-new");
	}

	@Test
	void raceThatStoresAnotherIdCleansUpNewProviderFile() {
		ByteArrayResource resource = new ByteArrayResource("%PDF-test".getBytes());
		when(persistenceService.claim(10L))
			.thenReturn(Optional.of(new UploadClaim(10L, "private-key")));
		when(fileStorage.load("private-key")).thenReturn(resource);
		when(aiClient.uploadFile(resource)).thenReturn("file-orphan");
		when(persistenceService.attachIfStillEligible(10L, "file-orphan"))
			.thenReturn(false);

		service().backfill(10L);

		verify(lifecycleService).deleteAfterCommit("file-orphan");
	}

	@Test
	void persistenceFailureAfterUploadAlsoCleansUpProviderFile() {
		ByteArrayResource resource = new ByteArrayResource("%PDF-test".getBytes());
		when(persistenceService.claim(10L))
			.thenReturn(Optional.of(new UploadClaim(10L, "private-key")));
		when(fileStorage.load("private-key")).thenReturn(resource);
		when(aiClient.uploadFile(resource)).thenReturn("file-orphan");
		doThrow(new IllegalStateException("database unavailable"))
			.when(persistenceService)
			.attachIfStillEligible(10L, "file-orphan");

		assertThatCode(() -> service().backfill(10L))
			.doesNotThrowAnyException();

		verify(lifecycleService).deleteAfterCommit("file-orphan");
	}

	@Test
	void uploadFailureLeavesReadyMaterialForBackoffRetry() {
		ByteArrayResource resource = new ByteArrayResource("%PDF-test".getBytes());
		when(persistenceService.claim(10L))
			.thenReturn(Optional.of(new UploadClaim(10L, "private-key")));
		when(fileStorage.load("private-key")).thenReturn(resource);
		doThrow(new IllegalStateException("upstream unavailable"))
			.when(aiClient)
			.uploadFile(resource);

		assertThatCode(() -> service().backfill(10L))
			.doesNotThrowAnyException();

		verify(persistenceService, never())
			.attachIfStillEligible(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
		verify(lifecycleService, never())
			.deleteAfterCommit(org.mockito.ArgumentMatchers.any());
	}

	private MaterialXaiFileBackfillService service() {
		return new MaterialXaiFileBackfillService(
			persistenceService,
			fileStorage,
			aiClient,
			lifecycleService
		);
	}
}
