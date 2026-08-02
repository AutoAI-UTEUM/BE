package io.edupilot.material;

import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialWithdrawalHookTest {
	private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");

	@Mock
	private LearningMaterialRepository materialRepository;

	@Test
	void withdrawalLogicallyDeletesOwnersMaterials() {
		new MaterialWithdrawalHook(
			materialRepository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		).onWithdraw(7L);

		verify(materialRepository).deleteAllActiveByOwnerId(7L, NOW);
	}
}
