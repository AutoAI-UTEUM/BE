package io.edupilot.material;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialXaiFileBackfillPersistenceService {

	private final LearningMaterialRepository materialRepository;
	private final MaterialXaiFileBackfillProperties properties;
	private final Clock clock;

	public MaterialXaiFileBackfillPersistenceService(
		LearningMaterialRepository materialRepository,
		MaterialXaiFileBackfillProperties properties,
		Clock clock
	) {
		this.materialRepository = materialRepository;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Long> findCandidates() {
		Instant retryCutoff = clock.instant().minus(properties.retryBackoff());
		return materialRepository.findXaiFileBackfillIds(
			retryCutoff,
			PageRequest.of(0, properties.batchSize())
		);
	}

	@Transactional
	public Optional<UploadClaim> claim(Long materialId) {
		Instant now = clock.instant();
		Instant retryCutoff = now.minus(properties.retryBackoff());
		return materialRepository.findByIdForUpdate(materialId)
			.filter(material -> material.claimXaiFileUpload(now, retryCutoff))
			.map(material -> new UploadClaim(
				material.getId(),
				material.getStorageKey()
			));
	}

	@Transactional
	public boolean attachIfStillEligible(Long materialId, String fileId) {
		return materialRepository.findByIdForUpdate(materialId)
			.map(material -> material.attachXaiFileIfMissing(fileId))
			.orElse(false);
	}

	public record UploadClaim(Long materialId, String storageKey) {
	}
}
