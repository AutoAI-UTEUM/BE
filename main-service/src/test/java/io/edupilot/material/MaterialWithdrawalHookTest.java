package io.edupilot.material;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialWithdrawalHookTest {
	private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");

	@Mock
	private LearningMaterialRepository materialRepository;

	@Mock
	private MaterialXaiFileLifecycleService xaiFileLifecycleService;

	@Test
	void withdrawalLogicallyDeletesOwnersMaterials() {
		when(materialRepository.findActiveXaiFilesByOwnerId(7L))
			.thenReturn(List.of("file-a", "file-b"));
		new MaterialWithdrawalHook(
			materialRepository,
			Clock.fixed(NOW, ZoneOffset.UTC),
			xaiFileLifecycleService
		).onWithdraw(7L);

		verify(materialRepository).deleteAllActiveByOwnerId(7L, NOW);
		verify(xaiFileLifecycleService).deleteAfterCommit("file-a");
		verify(xaiFileLifecycleService).deleteAfterCommit("file-b");
	}
}
