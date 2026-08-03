package io.edupilot.report;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportCriterionRepository extends JpaRepository<ReportCriterion, Long> {

	List<ReportCriterion> findByClassroom_IdAndActiveTrueOrderByCriterionKeyAscVersionDesc(
		Long classroomId
	);
}
