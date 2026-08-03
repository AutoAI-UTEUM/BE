package io.edupilot.report;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
