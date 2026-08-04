package io.edupilot.report;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentReportRepository extends JpaRepository<StudentReport, Long> {

	Optional<StudentReport> findByGeneration_Id(Long generationId);

	List<StudentReport> findByClassroom_IdAndStudent_IdOrderByVersionDesc(
		Long classroomId,
		Long studentId
	);

	Optional<StudentReport> findFirstByClassroom_IdAndStudent_IdOrderByVersionDesc(
		Long classroomId,
		Long studentId
	);
}
