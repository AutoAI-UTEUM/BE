package io.edupilot.material;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.DocChatRequest.ContextDocument;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Service
public class MaterialDocChatContextService {

	private final MaterialAccessService materialAccessService;
	private final MaterialPageRepository pageRepository;
	private final DocChatPageContextBuilder contextBuilder;

	public MaterialDocChatContextService(
		MaterialAccessService materialAccessService,
		MaterialPageRepository pageRepository,
		DocChatPageContextBuilder contextBuilder
	) {
		this.materialAccessService = materialAccessService;
		this.pageRepository = pageRepository;
		this.contextBuilder = contextBuilder;
	}

	@Transactional(readOnly = true)
	public List<ContextDocument> build(Long userId, Long materialId) {
		LearningMaterial material = requireReady(userId, materialId);
		return contextBuilder.build(
			material.getTitle(),
			pageRepository.findByMaterial_IdOrderByPageNumberAsc(materialId),
			10
		);
	}

	@Transactional(readOnly = true)
	public LearningMaterial requireReady(Long userId, Long materialId) {
		LearningMaterial material = materialAccessService.requireAccessible(
			userId,
			materialId
		);
		if (material.getProcessingStatus() == MaterialProcessingStatus.PROCESSING) {
			throw new BusinessException(ErrorCode.MATERIAL_PROCESSING);
		}
		if (material.getProcessingStatus() == MaterialProcessingStatus.FAILED) {
			throw new BusinessException(ErrorCode.MATERIAL_PROCESSING_FAILED);
		}
		return material;
	}
}
