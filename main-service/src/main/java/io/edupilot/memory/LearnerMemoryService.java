package io.edupilot.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialStatus;
import io.edupilot.memory.dto.LearnerMemoryResponse;

@Service
public class LearnerMemoryService {

	private final LearningMaterialRepository materialRepository;
	private final LearnerMemoryRepository memoryRepository;

	public LearnerMemoryService(
		LearningMaterialRepository materialRepository,
		LearnerMemoryRepository memoryRepository
	) {
		this.materialRepository = materialRepository;
		this.memoryRepository = memoryRepository;
	}

	@Transactional(readOnly = true)
	public LearnerMemoryResponse get(Long userId, Long materialId) {
		materialRepository.findByIdAndOwner_IdAndStatus(
				materialId,
				userId,
				MaterialStatus.ACTIVE
			)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		return memoryRepository.findByUser_IdAndMaterial_Id(
				userId,
				materialId
			)
			.map(memory -> LearnerMemoryResponse.from(materialId, memory))
			.orElseGet(() -> LearnerMemoryResponse.empty(materialId));
	}
}
