package io.edupilot.session;

import org.springframework.stereotype.Component;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.material.MaterialDeletionGuard;

@Component
public class SessionMaterialDeletionGuard implements MaterialDeletionGuard {

	private final LearningSessionRepository sessionRepository;
	private final ClassroomWeekMaterialRepository weekMaterialRepository;

	public SessionMaterialDeletionGuard(
		LearningSessionRepository sessionRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository
	) {
		this.sessionRepository = sessionRepository;
		this.weekMaterialRepository = weekMaterialRepository;
	}

	@Override
	public void assertDeletable(Long materialId) {
		if (weekMaterialRepository.existsByMaterial_Id(materialId)) {
			throw new BusinessException(ErrorCode.MATERIAL_LINKED_TO_CLASSROOM);
		}
		if (sessionRepository.existsByMaterial_IdAndStatus(
			materialId,
			SessionStatus.ACTIVE
		)) {
			throw new BusinessException(ErrorCode.MATERIAL_HAS_ACTIVE_SESSION);
		}
	}
}
