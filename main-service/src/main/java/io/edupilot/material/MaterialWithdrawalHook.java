package io.edupilot.material;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.user.UserWithdrawalHook;

@Component
public class MaterialWithdrawalHook implements UserWithdrawalHook {

	private final LearningMaterialRepository materialRepository;

	public MaterialWithdrawalHook(LearningMaterialRepository materialRepository) {
		this.materialRepository = materialRepository;
	}

	@Override
	@Transactional
	public void onWithdraw(Long userId) {
		materialRepository.deleteAllActiveByOwnerId(userId);
	}
}
