package io.edupilot.material;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialCaptionPersistenceService {

	private final LearningMaterialRepository materialRepository;
	private final MaterialPageRepository pageRepository;

	public MaterialCaptionPersistenceService(
		LearningMaterialRepository materialRepository,
		MaterialPageRepository pageRepository
	) {
		this.materialRepository = materialRepository;
		this.pageRepository = pageRepository;
	}

	@Transactional(readOnly = true)
	public Optional<CaptionSnapshot> snapshot(Long materialId) {
		LearningMaterial material = materialRepository.findById(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()
			|| material.getCaptionsCompletedAt() != null) {
			return Optional.empty();
		}
		List<PageSnapshot> pages = pageRepository
			.findByMaterial_IdOrderByPageNumberAsc(materialId)
			.stream()
			.map(page -> new PageSnapshot(
				page.getPageNumber(),
				page.getTextContent()
			))
			.toList();
		return Optional.of(new CaptionSnapshot(
			material.getOwnerId(),
			material.getStorageKey(),
			pages
		));
	}

	@Transactional
	public void applyCaptions(Long materialId, Map<Integer, String> captions) {
		if (captions.isEmpty()) {
			return;
		}
		LearningMaterial material = materialRepository.findById(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()
			|| material.getCaptionsCompletedAt() != null) {
			return;
		}
		pageRepository.findByMaterial_IdOrderByPageNumberAsc(materialId)
			.forEach(page -> {
				String caption = captions.get(page.getPageNumber());
				if (caption != null) {
					page.updateCaption(caption);
				}
			});
	}

	@Transactional
	public boolean markCompleted(Long materialId, Instant completedAt) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()
			|| material.getCaptionsCompletedAt() != null) {
			return false;
		}
		material.completeCaptionGeneration(completedAt);
		return true;
	}

	@Transactional(readOnly = true)
	public List<Long> findBackfillCandidates(int batchSize) {
		return materialRepository.findMissingCaptionIds(PageRequest.of(0, batchSize));
	}

	public record CaptionSnapshot(
		Long ownerId,
		String storageKey,
		List<PageSnapshot> pages
	) {
	}

	public record PageSnapshot(int pageNumber, String text) {
	}
}
