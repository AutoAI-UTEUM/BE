package io.edupilot.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;

@Service
public class LearningProgressService {

	private final LearningSessionRepository sessionRepository;
	private final LearningMaterialRepository materialRepository;
	private final SessionPageRecordRepository pageRecordRepository;

	public LearningProgressService(
		LearningSessionRepository sessionRepository,
		LearningMaterialRepository materialRepository,
		SessionPageRecordRepository pageRecordRepository
	) {
		this.sessionRepository = sessionRepository;
		this.materialRepository = materialRepository;
		this.pageRecordRepository = pageRecordRepository;
	}

	@Transactional(readOnly = true)
	public int calculateSessionProgressRate(Long userId, Long sessionId) {
		LearningSession session = sessionRepository
			.findByIdAndUser_Id(sessionId, userId)
			.filter(candidate -> candidate.getStatus() != SessionStatus.DELETED)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		return progressRate(
			pageRecordRepository.countBySessionId(sessionId),
			session.getMaterialPageCount()
		);
	}

	@Transactional(readOnly = true)
	public int calculateMaterialProgressRate(Long userId, Long materialId) {
		LearningMaterial material = materialRepository.findById(materialId)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		return progressRate(
			pageRecordRepository.countDistinctByUserIdAndMaterialId(
				userId,
				materialId
			),
			material.getPageCount()
		);
	}

	private int progressRate(long explainedPageCount, Integer pageCount) {
		if (explainedPageCount == 0 || pageCount == null || pageCount < 1) {
			return 0;
		}
		return (int) Math.round(explainedPageCount * 100.0 / pageCount);
	}
}
