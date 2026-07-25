package io.edupilot.material;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LearningMaterialRepository
	extends JpaRepository<LearningMaterial, Long> {

	Page<LearningMaterial> findByOwner_IdAndStatus(
		Long ownerId,
		MaterialStatus status,
		Pageable pageable
	);

	Optional<LearningMaterial> findByIdAndOwner_IdAndStatus(
		Long id,
		Long ownerId,
		MaterialStatus status
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select material from LearningMaterial material where material.id = :id")
	Optional<LearningMaterial> findByIdForUpdate(@Param("id") Long id);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update LearningMaterial material
		set material.status = io.edupilot.material.MaterialStatus.DELETED
		where material.owner.id = :ownerId
		  and material.status = io.edupilot.material.MaterialStatus.ACTIVE
		""")
	int deleteAllActiveByOwnerId(@Param("ownerId") Long ownerId);
}
