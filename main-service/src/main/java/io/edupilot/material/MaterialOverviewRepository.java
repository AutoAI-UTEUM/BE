package io.edupilot.material;

import java.util.Optional;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialOverviewRepository
	extends JpaRepository<MaterialOverview, Long> {

	Optional<MaterialOverview> findByMaterial_Id(Long materialId);

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
