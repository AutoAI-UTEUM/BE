package io.edupilot.report;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportCriterionRepository extends JpaRepository<ReportCriterion, Long> {

	List<ReportCriterion> findByClassroom_IdAndActiveTrueOrderByCriterionKeyAscVersionDesc(
		Long classroomId
	);

	Optional<ReportCriterion> findByIdAndClassroom_Id(Long id, Long classroomId);

	List<ReportCriterion> findByClassroom_IdAndCriterionKeyOrderByVersionDesc(
		Long classroomId,
		String criterionKey
	);

	boolean existsByClassroom_IdAndCriterionKey(Long classroomId, String criterionKey);

	long countByClassroom_IdAndActiveTrue(Long classroomId);
}
