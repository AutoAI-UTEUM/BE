package io.edupilot.material;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialWithdrawalHookTest {

	@Mock
	private LearningMaterialRepository materialRepository;

	@Test
	void withdrawalLogicallyDeletesOwnersMaterials() {
		new MaterialWithdrawalHook(materialRepository).onWithdraw(7L);

		verify(materialRepository).deleteAllActiveByOwnerId(7L);
	}
}
