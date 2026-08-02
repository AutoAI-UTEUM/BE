package io.edupilot.material;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.user.UserWithdrawalHook;

@Component
public class MaterialWithdrawalHook implements UserWithdrawalHook {

	private final LearningMaterialRepository materialRepository;
	private final Clock clock;

	public MaterialWithdrawalHook(
		LearningMaterialRepository materialRepository,
		Clock clock
	) {
		this.materialRepository = materialRepository;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void onWithdraw(Long userId) {
		materialRepository.deleteAllActiveByOwnerId(userId, clock.instant());
	}
}
