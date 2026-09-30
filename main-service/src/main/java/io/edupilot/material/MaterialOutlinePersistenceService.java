package io.edupilot.material;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
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
	private static final int MAX_GENERATION_FAILURES = 3;

	private final LearningMaterialRepository materialRepository;
	private final MaterialPageRepository pageRepository;
	private final MaterialOverviewRepository overviewRepository;
	private final MaterialPageTextMerger pageTextMerger;
	private final Clock clock;
	private final PageQuizPlanProperties pageQuizPlanProperties;

	public MaterialOutlinePersistenceService(
		LearningMaterialRepository materialRepository,
		MaterialPageRepository pageRepository,
		MaterialOverviewRepository overviewRepository,
		MaterialPageTextMerger pageTextMerger,
		Clock clock,
		PageQuizPlanProperties pageQuizPlanProperties
	) {
		this.materialRepository = materialRepository;
		this.pageRepository = pageRepository;
		this.overviewRepository = overviewRepository;
		this.pageTextMerger = pageTextMerger;
		this.clock = clock;
		this.pageQuizPlanProperties = pageQuizPlanProperties;
	}

	@Transactional(readOnly = true)
	public Optional<OutlineSnapshot> snapshot(Long materialId) {
		return snapshot(materialId, false);
	}

	@Transactional(readOnly = true)
	public Optional<OutlineSnapshot> snapshotForManual(Long materialId) {
		return snapshot(materialId, true);
	}

	private Optional<OutlineSnapshot> snapshot(Long materialId, boolean manual) {
		LearningMaterial material = materialRepository.findById(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()) {
			return Optional.empty();
		}
		Optional<MaterialOverview> overview = overviewRepository
			.findByMaterial_Id(materialId);
		if (!manual && overview.isPresent() && !canGenerate(overview.get())) {
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
	public boolean claimAutomaticGeneration(Long materialId) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()) {
			return false;
		}
		MaterialOverview overview = overviewRepository.findByMaterial_Id(materialId)
			.orElse(null);
		Instant cutoff = clock.instant().minus(FAILED_RETRY_BACKOFF);
		if (overview != null && !canClaimAutomatically(overview, cutoff)) {
			return false;
		}
		if (overview == null) {
			overview = MaterialOverview.createPending(material);
		}
		overview.claimGeneration(clock.instant());
		overviewRepository.save(overview);
		return true;
	}

	@Transactional
	public boolean prepareManualRegeneration(Long materialId) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()) {
			return false;
		}
		MaterialOverview overview = overviewRepository.findByMaterial_Id(materialId)
			.orElseGet(() -> MaterialOverview.createPending(material));
		overview.clearGenerationFailures();
		overview.claimGeneration(clock.instant());
		overviewRepository.save(overview);
		return true;
	}

	@Transactional
	public boolean markReady(
		Long materialId,
		String content,
		OutlineResponse outline
	) {
		return markReady(materialId, content, outline, false);
	}

	@Transactional
	public boolean markReadyManual(
		Long materialId,
		String content,
		OutlineResponse outline
	) {
		return markReady(materialId, content, outline, true);
	}

	private boolean markReady(
		Long materialId,
		String content,
		OutlineResponse outline,
		boolean manual
	) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.orElse(null);
		if (material == null || !material.isActive() || !material.isReady()) {
			return false;
		}
		MaterialOverview overview = overviewRepository.findByMaterial_Id(materialId)
			.orElseGet(() -> MaterialOverview.createPending(material));
		if (!manual && overview.getStatus() == MaterialOverviewStatus.READY
			&& !needsCheckpointBackfill(overview)
			&& !needsPageQuizPlanBackfill(overview)) {
			return false;
		}
		overview.markReady(content, outline);
		if (outline.quizCheckpoints() == null
			|| (pageQuizPlanProperties.enabled()
				&& outline.pageQuizPlan() == null)) {
			// A valid overview can still miss optional backfill output.
			overview.recordFailedGeneration(clock.instant());
		} else {
			overview.clearGenerationFailures();
		}
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
			// Preserve a usable overview while backing off failed regeneration.
			overview.recordFailedGeneration(clock.instant());
		} else {
			overview.markFailed(clock.instant());
		}
		overviewRepository.save(overview);
		return true;
	}

	@Transactional(readOnly = true)
	public List<Long> findBackfillCandidates(int batchSize) {
		Instant cutoff = clock.instant().minus(FAILED_RETRY_BACKOFF);
		LinkedHashSet<Long> candidates = new LinkedHashSet<>(
			materialRepository.findMissingOverviewIds(PageRequest.of(0, batchSize))
		);
		int remainingSlots = batchSize - candidates.size();
		if (remainingSlots == 0) {
			return List.copyOf(candidates);
		}
		candidates.addAll(overviewRepository.findRetryableUnreadyMaterialIds(
			cutoff,
			MAX_GENERATION_FAILURES,
			PageRequest.of(0, remainingSlots)
		));
		remainingSlots = batchSize - candidates.size();
		if (remainingSlots == 0) {
			return List.copyOf(candidates);
		}
		candidates.addAll(
			overviewRepository.findReadyWithoutQuizCheckpointsMaterialIds(
				cutoff,
				MAX_GENERATION_FAILURES,
				PageRequest.of(0, remainingSlots)
			)
		);
		remainingSlots = batchSize - candidates.size();
		if (remainingSlots > 0 && pageQuizPlanProperties.enabled()
			&& pageQuizPlanProperties.backfillEnabled()) {
			// A READY outline with an absent plan keeps the legacy turn path.
			for (Long materialId : overviewRepository
				.findReadyWithoutPageQuizPlanMaterialIds(
					cutoff,
					MAX_GENERATION_FAILURES,
					PageRequest.of(0, batchSize + candidates.size())
				)) {
				if (!candidates.contains(materialId)) {
					candidates.add(materialId);
					if (--remainingSlots == 0) {
						break;
					}
				}
			}
		}
		return List.copyOf(candidates);
	}

	private boolean canClaimAutomatically(MaterialOverview overview, Instant cutoff) {
		if (overview.getGenerationFailureCount() >= MAX_GENERATION_FAILURES
			|| (overview.getGenerationAttemptedAt() != null
				&& overview.getGenerationAttemptedAt().isAfter(cutoff))) {
			return false;
		}
		if (overview.getStatus() == MaterialOverviewStatus.PENDING
			|| overview.getStatus() == MaterialOverviewStatus.FAILED) {
			return overview.getGenerationAttemptedAt() != null
				|| overview.getUpdatedAt() == null
				|| !overview.getUpdatedAt().isAfter(cutoff);
		}
		return needsCheckpointBackfill(overview)
			|| (needsPageQuizPlanBackfill(overview)
				&& overview.getUpdatedAt() != null
				&& !overview.getUpdatedAt().isAfter(cutoff));
	}

	private boolean canGenerate(MaterialOverview overview) {
		return overview.getStatus() == MaterialOverviewStatus.PENDING
			|| overview.getStatus() == MaterialOverviewStatus.FAILED
			|| needsCheckpointBackfill(overview)
			|| needsPageQuizPlanBackfill(overview);
	}

	private boolean needsCheckpointBackfill(MaterialOverview overview) {
		OutlineResponse outline = overview.getOutline();
		return overview.getStatus() == MaterialOverviewStatus.READY
			&& (outline == null || outline.quizCheckpoints() == null);
	}

	private boolean needsPageQuizPlanBackfill(MaterialOverview overview) {
		if (!pageQuizPlanProperties.enabled()
			|| !pageQuizPlanProperties.backfillEnabled()
			|| overview.getStatus() != MaterialOverviewStatus.READY) {
			return false;
		}
		OutlineResponse outline = overview.getOutline();
		return outline == null || outline.pageQuizPlan() == null;
	}

	public record OutlineSnapshot(
		Long ownerId,
		int totalPages,
		String xaiFileId,
		List<OutlineRequest.Page> pages
	) {
	}
}
