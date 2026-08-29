package io.edupilot.material;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.OutlineRequest;
import io.edupilot.ai.dto.OutlineResponse;

@Service
public class MaterialOutlinePersistenceService {

	private static final Duration FAILED_RETRY_BACKOFF = Duration.ofHours(24);

	private final LearningMaterialRepository materialRepository;
	private final MaterialPageRepository pageRepository;
	private final MaterialOverviewRepository overviewRepository;
	private final MaterialPageTextMerger pageTextMerger;
	private final Clock clock;

	public MaterialOutlinePersistenceService(
		LearningMaterialRepository materialRepository,
		MaterialPageRepository pageRepository,
		MaterialOverviewRepository overviewRepository,
		MaterialPageTextMerger pageTextMerger,
		Clock clock
	) {
		this.materialRepository = materialRepository;
		this.pageRepository = pageRepository;
		this.overviewRepository = overviewRepository;
		this.pageTextMerger = pageTextMerger;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Optional<OutlineSnapshot> snapshot(Long materialId) {
		LearningMaterial material = materialRepository.findById(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()) {
			return Optional.empty();
		}
		Optional<MaterialOverview> overview = overviewRepository
			.findByMaterial_Id(materialId);
		if (overview.isPresent() && !canGenerate(overview.get())) {
			return Optional.empty();
		}

		List<OutlineRequest.Page> pages = pageRepository
			.findByMaterial_IdOrderByPageNumberAsc(materialId)
			.stream()
			.map(page -> new OutlineRequest.Page(
				page.getPageNumber(),
				pageTextMerger.mergeCaption(
					page.getTextContent(),
					page.getCaption()
				)
			))
			.toList();
		return Optional.of(new OutlineSnapshot(
			material.getOwnerId(),
			material.getPageCount(),
			material.getXaiFileId(),
			pages
		));
	}

	@Transactional
	public boolean markReady(
		Long materialId,
		String content,
		OutlineResponse outline
	) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()) {
			return false;
		}
		MaterialOverview overview = overviewRepository.findByMaterial_Id(materialId)
			.orElseGet(() -> MaterialOverview.createPending(material));
		if (overview.getStatus() == MaterialOverviewStatus.READY
			&& !needsCheckpointBackfill(overview)) {
			return false;
		}
		overview.markReady(content, outline);
		overviewRepository.save(overview);
		return true;
	}

	@Transactional
	public boolean markFailed(Long materialId) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()) {
			return false;
		}
		MaterialOverview overview = overviewRepository.findByMaterial_Id(materialId)
			.orElseGet(() -> MaterialOverview.createPending(material));
		if (overview.getStatus() == MaterialOverviewStatus.READY) {
			return false;
		}
		overview.markFailed(clock.instant());
		overviewRepository.save(overview);
		return true;
	}

	@Transactional(readOnly = true)
	public List<Long> findBackfillCandidates(int batchSize) {
		List<Long> candidates = new ArrayList<>(
			materialRepository.findMissingOverviewIds(
				PageRequest.of(0, batchSize)
			)
		);
		int remainingSlots = batchSize - candidates.size();
		if (remainingSlots == 0) {
			return List.copyOf(candidates);
		}
		candidates.addAll(overviewRepository.findRetryableFailedMaterialIds(
			clock.instant().minus(FAILED_RETRY_BACKOFF),
			PageRequest.of(0, remainingSlots)
		));
		remainingSlots = batchSize - candidates.size();
		if (remainingSlots == 0) {
			return List.copyOf(candidates);
		}
		candidates.addAll(
			overviewRepository.findReadyWithoutQuizCheckpointsMaterialIds(
				PageRequest.of(0, remainingSlots)
			)
		);
		return List.copyOf(candidates);
	}

	private boolean canGenerate(MaterialOverview overview) {
		return overview.getStatus() == MaterialOverviewStatus.PENDING
			|| overview.getStatus() == MaterialOverviewStatus.FAILED
			|| needsCheckpointBackfill(overview);
	}

	private boolean needsCheckpointBackfill(MaterialOverview overview) {
		OutlineResponse outline = overview.getOutline();
		return overview.getStatus() == MaterialOverviewStatus.READY
			&& (outline == null || outline.quizCheckpoints() == null);
	}

	public record OutlineSnapshot(
		Long ownerId,
		int totalPages,
		String xaiFileId,
		List<OutlineRequest.Page> pages
	) {
	}
}
