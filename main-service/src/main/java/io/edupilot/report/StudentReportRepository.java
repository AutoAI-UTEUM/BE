package io.edupilot.report;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentReportRepository extends JpaRepository<StudentReport, Long> {

	List<StudentReport> findByClassroom_IdAndStudent_IdOrderByVersionDesc(
		Long classroomId,
		Long studentId
	);
}
