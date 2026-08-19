package io.edupilot.material;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.OutlineRequest;
import io.edupilot.ai.dto.OutlineResponse;

@Service
public class MaterialOutlinePersistenceService {

	private final LearningMaterialRepository materialRepository;
	private final MaterialPageRepository pageRepository;
	private final MaterialOverviewRepository overviewRepository;

	public MaterialOutlinePersistenceService(
		LearningMaterialRepository materialRepository,
		MaterialPageRepository pageRepository,
		MaterialOverviewRepository overviewRepository
	) {
		this.materialRepository = materialRepository;
		this.pageRepository = pageRepository;
		this.overviewRepository = overviewRepository;
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
		if (overview.isPresent()
			&& overview.get().getStatus() != MaterialOverviewStatus.PENDING) {
			return Optional.empty();
		}

		List<OutlineRequest.Page> pages = pageRepository
			.findByMaterial_IdOrderByPageNumberAsc(materialId)
			.stream()
			.map(page -> new OutlineRequest.Page(
				page.getPageNumber(),
				page.getTextContent()
			))
			.toList();
		return Optional.of(new OutlineSnapshot(material.getPageCount(), pages));
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
		if (overview.getStatus() == MaterialOverviewStatus.READY) {
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
		overview.markFailed();
		overviewRepository.save(overview);
		return true;
	}

	@Transactional(readOnly = true)
	public List<Long> findBackfillCandidates(int batchSize) {
		return materialRepository.findMissingOverviewIds(
			PageRequest.of(0, batchSize)
		);
	}

	public record OutlineSnapshot(
		int totalPages,
		List<OutlineRequest.Page> pages
	) {
	}
}
