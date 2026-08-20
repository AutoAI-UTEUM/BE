package io.edupilot.material;

import java.time.Instant;
import java.util.List;
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

	@Query("select material.id from LearningMaterial material "
		+ "where material.status = io.edupilot.material.MaterialStatus.ACTIVE "
		+ "and material.processingStatus = "
		+ "io.edupilot.material.MaterialProcessingStatus.PROCESSING "
		+ "and material.updatedAt <= :cutoff "
		+ "order by material.updatedAt, material.id")
	List<Long> findStuckProcessingIds(
		@Param("cutoff") Instant cutoff,
		Pageable pageable
	);

	@Query("select material.id from LearningMaterial material "
		+ "where material.status = io.edupilot.material.MaterialStatus.ACTIVE "
		+ "and material.processingStatus = "
		+ "io.edupilot.material.MaterialProcessingStatus.READY "
		+ "and not exists (select overview.id from MaterialOverview overview "
		+ "where overview.material = material) "
		+ "order by material.createdAt, material.id")
	List<Long> findMissingOverviewIds(Pageable pageable);

	@Query("select material.id from LearningMaterial material "
		+ "where material.status = io.edupilot.material.MaterialStatus.ACTIVE "
		+ "and material.processingStatus = "
		+ "io.edupilot.material.MaterialProcessingStatus.READY "
		+ "and material.captionsCompletedAt is null "
		+ "order by material.createdAt, material.id")
	List<Long> findMissingCaptionIds(Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update LearningMaterial material "
		+ "set material.pageCount = null, "
		+ "material.processingStatus = "
		+ "io.edupilot.material.MaterialProcessingStatus.FAILED, "
		+ "material.failureReason = "
		+ "io.edupilot.material.MaterialFailureReason.EXTRACTION_FAILED, "
		+ "material.failureTraceId = null, material.updatedAt = :now "
		+ "where material.id in :materialIds "
		+ "and material.status = io.edupilot.material.MaterialStatus.ACTIVE "
		+ "and material.processingStatus = "
		+ "io.edupilot.material.MaterialProcessingStatus.PROCESSING "
		+ "and material.updatedAt <= :cutoff")
	int failStuckProcessing(
		@Param("materialIds") List<Long> materialIds,
		@Param("cutoff") Instant cutoff,
		@Param("now") Instant now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update LearningMaterial material
		set material.status = io.edupilot.material.MaterialStatus.DELETED,
		    material.updatedAt = :updatedAt
		where material.owner.id = :ownerId
		  and material.status = io.edupilot.material.MaterialStatus.ACTIVE
		""")
	int deleteAllActiveByOwnerId(
		@Param("ownerId") Long ownerId,
		@Param("updatedAt") Instant updatedAt
	);
}
