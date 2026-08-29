package io.edupilot.material;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
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
				material.getOwnerId(),
				material.getStorageKey()
			));
	}

	@Transactional
	public boolean complete(Long materialId, List<ExtractedPage> extractedPages) {
		return complete(materialId, extractedPages, null).applied();
	}

	@Transactional
	public CompletionResult complete(
		Long materialId,
		List<ExtractedPage> extractedPages,
		String xaiFileId
	) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.orElse(null);
		if (material == null || !material.isActiveAndProcessing()) {
			return CompletionResult.discarded();
		}

		List<MaterialPage> pages = extractedPages.stream()
			.map(page -> MaterialPage.create(
				material,
				page.pageNumber(),
				page.text()
			))
			.toList();
		pageRepository.saveAll(pages);
		String replacedXaiFileId = material.replaceXaiFileId(xaiFileId);
		material.markReady(pages.size());
		return new CompletionResult(true, replacedXaiFileId);
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

	@Transactional
	public int failStuckExtractions(
		Instant cutoff,
		Instant now,
		int batchSize
	) {
		List<Long> materialIds = materialRepository.findStuckProcessingIds(
			cutoff,
			PageRequest.of(0, batchSize)
		);
		if (materialIds.isEmpty()) {
			return 0;
		}
		return materialRepository.failStuckProcessing(materialIds, cutoff, now);
	}

	public record ExtractionSnapshot(
		Long materialId,
		Long ownerId,
		String storageKey
	) {
	}

	public record CompletionResult(
		boolean applied,
		String replacedXaiFileId
	) {
		private static CompletionResult discarded() {
			return new CompletionResult(false, null);
		}
	}
}
