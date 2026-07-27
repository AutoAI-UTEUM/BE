package io.edupilot.memory;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LearnerMemoryCandidateRepository
	extends JpaRepository<LearnerMemoryCandidate, Long> {

	List<LearnerMemoryCandidate>
		findByUser_IdAndMaterial_IdAndStatusOrderByCreatedAtDescIdDesc(
			Long userId,
			Long materialId,
			MemoryCandidateStatus status
		);

	List<LearnerMemoryCandidate> findByIdInAndUser_IdAndMaterial_IdAndStatus(
		Collection<Long> ids,
		Long userId,
		Long materialId,
		MemoryCandidateStatus status
	);
}
