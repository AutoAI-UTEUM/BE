package io.edupilot.material;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.material.dto.MaterialOverviewResponse;

@Service
public class MaterialOverviewService {

	private final MaterialAccessService accessService;
	private final MaterialOverviewRepository overviewRepository;

	public MaterialOverviewService(
		MaterialAccessService accessService,
		MaterialOverviewRepository overviewRepository
	) {
		this.accessService = accessService;
		this.overviewRepository = overviewRepository;
	}

	@Transactional(readOnly = true)
	public MaterialOverviewResponse get(Long userId, Long materialId) {
		LearningMaterial material = accessService.requireAccessible(userId, materialId);
		return overviewRepository.findByMaterial_Id(materialId)
			.map(MaterialOverviewResponse::from)
			.orElseGet(() -> MaterialOverviewResponse.pending(material.getId()));
	}
}
