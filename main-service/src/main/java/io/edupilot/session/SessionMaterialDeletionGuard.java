package io.edupilot.session;

import org.springframework.stereotype.Component;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialDeletionGuard;

@Component
public class SessionMaterialDeletionGuard implements MaterialDeletionGuard {

	private final LearningSessionRepository sessionRepository;

	public SessionMaterialDeletionGuard(
		LearningSessionRepository sessionRepository
	) {
		this.sessionRepository = sessionRepository;
	}

	@Override
	public void assertDeletable(Long materialId) {
		if (sessionRepository.existsByMaterial_IdAndStatus(
			materialId,
			SessionStatus.ACTIVE
		)) {
			throw new BusinessException(ErrorCode.MATERIAL_HAS_ACTIVE_SESSION);
		}
	}
}
