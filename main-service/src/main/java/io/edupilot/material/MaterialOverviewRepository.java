package io.edupilot.material;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialOverviewRepository
	extends JpaRepository<MaterialOverview, Long> {

	Optional<MaterialOverview> findByMaterial_Id(Long materialId);

	@Query("select overview.material.id from MaterialOverview overview "
		+ "where overview.material.status = "
		+ "io.edupilot.material.MaterialStatus.ACTIVE "
		+ "and overview.material.processingStatus = "
		+ "io.edupilot.material.MaterialProcessingStatus.READY "
		+ "and overview.status = "
		+ "io.edupilot.material.MaterialOverviewStatus.FAILED "
		+ "and overview.updatedAt <= :cutoff "
		+ "order by overview.updatedAt, overview.material.id")
	List<Long> findRetryableFailedMaterialIds(
		@Param("cutoff") Instant cutoff,
		Pageable pageable
	);

	@Query("select overview.material.id from MaterialOverview overview "
		+ "where overview.material.status = "
		+ "io.edupilot.material.MaterialStatus.ACTIVE "
		+ "and overview.material.processingStatus = "
		+ "io.edupilot.material.MaterialProcessingStatus.READY "
		+ "and overview.status = "
		+ "io.edupilot.material.MaterialOverviewStatus.READY "
		+ "and (function('json_query', overview.outline, "
		+ "'$.quizCheckpoints') is null "
		+ "or cast(function('json_query', overview.outline, "
		+ "'$.quizCheckpoints') as String) = 'null') "
		+ "order by overview.updatedAt, overview.material.id")
	List<Long> findReadyWithoutQuizCheckpointsMaterialIds(Pageable pageable);

	@EntityGraph(attributePaths = "material")
	@Query("""
		select distinct overview
		from MaterialOverview overview
		join ClassroomWeekMaterial link on link.material = overview.material
		where link.week.classroom.id = :classroomId
		  and overview.status = io.edupilot.material.MaterialOverviewStatus.READY
		  and overview.material.status = io.edupilot.material.MaterialStatus.ACTIVE
		  and overview.material.processingStatus = io.edupilot.material.MaterialProcessingStatus.READY
		order by overview.material.id
		""")
	List<MaterialOverview> findReadyByClassroomId(
		@Param("classroomId") Long classroomId
	);
}
