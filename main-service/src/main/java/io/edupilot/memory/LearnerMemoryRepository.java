package io.edupilot.memory;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LearnerMemoryRepository
	extends JpaRepository<LearnerMemory, Long> {

	Optional<LearnerMemory> findByUser_IdAndMaterial_Id(
		Long userId,
		Long materialId
	);
}
