package io.edupilot.material;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.ExtractedPage;

@Service
public class MaterialExtractionPersistenceService {

	private final LearningMaterialRepository materialRepository;
	private final MaterialPageRepository pageRepository;

	public MaterialExtractionPersistenceService(
		LearningMaterialRepository materialRepository,
		MaterialPageRepository pageRepository
	) {
		this.materialRepository = materialRepository;
		this.pageRepository = pageRepository;
	}

	@Transactional(readOnly = true)
	public Optional<ExtractionSnapshot> snapshot(Long materialId) {
		return materialRepository.findById(materialId)
			.filter(LearningMaterial::isActiveAndProcessing)
			.map(material -> new ExtractionSnapshot(
				material.getId(),
				material.getStorageKey()
			));
	}

	@Transactional
	public boolean complete(Long materialId, List<ExtractedPage> extractedPages) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.orElse(null);
		if (material == null || !material.isActiveAndProcessing()) {
			return false;
		}

		List<MaterialPage> pages = extractedPages.stream()
			.map(page -> MaterialPage.create(
				material,
				page.pageNumber(),
				page.text()
			))
			.toList();
		pageRepository.saveAll(pages);
		material.markReady(pages.size());
		return true;
	}

	@Transactional
	public boolean fail(
		Long materialId,
		MaterialFailureReason failureReason,
		String traceId
	) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.orElse(null);
		if (material == null || !material.isActiveAndProcessing()) {
			return false;
		}
		material.markFailed(failureReason, traceId);
		return true;
	}

	public record ExtractionSnapshot(
		Long materialId,
		String storageKey
	) {
	}
}
