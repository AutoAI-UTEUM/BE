package io.edupilot.material;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;

@Service
public class MaterialAccessService {

	private final LearningMaterialRepository materialRepository;
	private final ClassroomWeekMaterialRepository weekMaterialRepository;
	private final LearningSessionRepository sessionRepository;
	private final Clock clock;

	public MaterialAccessService(
		LearningMaterialRepository materialRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		LearningSessionRepository sessionRepository,
		Clock clock
	) {
		this.materialRepository = materialRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.sessionRepository = sessionRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public LearningMaterial requireAccessible(Long userId, Long materialId) {
		LearningMaterial material = materialRepository.findById(materialId)
			.filter(LearningMaterial::isActive)
			.orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		return assertAccessible(userId, material);
	}

	@Transactional
	public LearningMaterial requireAccessibleForUpdate(
		Long userId,
		Long materialId
	) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.filter(LearningMaterial::isActive)
			.orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		return assertAccessible(userId, material);
	}

	@Transactional(readOnly = true)
	public void assertAccessible(Long userId, Long materialId) {
		requireAccessible(userId, materialId);
	}

	@Transactional(readOnly = true)
	public void assertSessionAccessible(Long userId, Long sessionId) {
		LearningSession session = sessionRepository.findByIdAndUser_Id(
				sessionId,
				userId
			)
			.filter(candidate -> candidate.getStatus() != SessionStatus.DELETED)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		assertAccessible(userId, session.getMaterialId());
	}

	private LearningMaterial assertAccessible(
		Long userId,
		LearningMaterial material
	) {
		if (material.getOwnerId().equals(userId)
			|| weekMaterialRepository.existsVisibleAccess(
				userId,
				material.getId(),
				clock.instant()
			)) {
			return material;
		}
		throw new BusinessException(ErrorCode.MATERIAL_NOT_FOUND);
	}
}
