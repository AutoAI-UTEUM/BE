package io.edupilot.material;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.user.UserWithdrawalHook;

@Component
public class MaterialWithdrawalHook implements UserWithdrawalHook {

	private final LearningMaterialRepository materialRepository;
	private final Clock clock;
	private final MaterialXaiFileLifecycleService xaiFileLifecycleService;

	public MaterialWithdrawalHook(
		LearningMaterialRepository materialRepository,
		Clock clock,
		MaterialXaiFileLifecycleService xaiFileLifecycleService
	) {
		this.materialRepository = materialRepository;
		this.clock = clock;
		this.xaiFileLifecycleService = xaiFileLifecycleService;
	}

	@Override
	@Transactional
	public void onWithdraw(Long userId) {
		var xaiFiles = materialRepository.findActiveXaiFilesByOwnerId(userId);
		materialRepository.deleteAllActiveByOwnerId(userId, clock.instant());
		xaiFiles.forEach(xaiFileLifecycleService::deleteAfterCommit);
	}
}
