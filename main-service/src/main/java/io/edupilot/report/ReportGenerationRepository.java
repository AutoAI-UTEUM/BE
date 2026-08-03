package io.edupilot.report;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReportGenerationRepository extends JpaRepository<ReportGeneration, Long> {

	Optional<ReportGeneration> findByClassroom_IdAndStudent_IdAndRequestId(
		Long classroomId,
		Long studentId,
		String requestId
	);

	Optional<ReportGeneration>
	findFirstByClassroom_IdAndStudent_IdAndScopeHashAndStatusInOrderByCreatedAtAsc(
		Long classroomId,
		Long studentId,
		String scopeHash,
		Collection<ReportGenerationStatus> statuses
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update ReportGeneration generation "
		+ "set generation.status = io.edupilot.report.ReportGenerationStatus.PROCESSING, "
		+ "generation.failureCode = null, "
		+ "generation.generationLeaseToken = :leaseToken, "
		+ "generation.generationLeaseUntil = :leaseUntil, "
		+ "generation.updatedAt = :now "
		+ "where generation.id = :generationId "
		+ "and generation.status in ("
		+ "io.edupilot.report.ReportGenerationStatus.PENDING, "
		+ "io.edupilot.report.ReportGenerationStatus.PROCESSING) "
		+ "and generation.generationLeaseUntil < :now")
	int claimGenerationLease(
		@Param("generationId") Long generationId,
		@Param("leaseToken") String leaseToken,
		@Param("now") Instant now,
		@Param("leaseUntil") Instant leaseUntil
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select generation from ReportGeneration generation "
		+ "where generation.id = :generationId")
	Optional<ReportGeneration> findByIdForUpdate(
		@Param("generationId") Long generationId
	);

	@Query("select generation.id from ReportGeneration generation "
		+ "where generation.status in ("
		+ "io.edupilot.report.ReportGenerationStatus.PENDING, "
		+ "io.edupilot.report.ReportGenerationStatus.PROCESSING) "
		+ "and generation.createdAt <= :cutoff "
		+ "order by generation.createdAt, generation.id")
	List<Long> findExpiredGenerationIds(
		@Param("cutoff") Instant cutoff,
		Pageable pageable
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update ReportGeneration generation "
		+ "set generation.status = io.edupilot.report.ReportGenerationStatus.FAILED, "
		+ "generation.failureCode = :failureCode, "
		+ "generation.generationLeaseToken = null, "
		+ "generation.generationLeaseUntil = :noLease, "
		+ "generation.updatedAt = :now "
		+ "where generation.id in :generationIds "
		+ "and generation.status in ("
		+ "io.edupilot.report.ReportGenerationStatus.PENDING, "
		+ "io.edupilot.report.ReportGenerationStatus.PROCESSING) "
		+ "and generation.createdAt <= :cutoff")
	int failExpiredGenerations(
		@Param("generationIds") List<Long> generationIds,
		@Param("cutoff") Instant cutoff,
		@Param("failureCode") String failureCode,
		@Param("noLease") Instant noLease,
		@Param("now") Instant now
	);

	@Query("select generation from ReportGeneration generation "
		+ "where generation.status in ("
		+ "io.edupilot.report.ReportGenerationStatus.PENDING, "
		+ "io.edupilot.report.ReportGenerationStatus.PROCESSING) "
		+ "and generation.createdAt > :cutoff "
		+ "and generation.generationLeaseUntil < :now "
		+ "order by generation.createdAt, generation.id")
	List<ReportGeneration> findRecoverableGenerations(
		@Param("cutoff") Instant cutoff,
		@Param("now") Instant now,
		Pageable pageable
	);
}
