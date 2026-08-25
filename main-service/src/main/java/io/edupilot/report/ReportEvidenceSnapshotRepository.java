package io.edupilot.report;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportEvidenceSnapshotRepository
	extends JpaRepository<ReportEvidenceSnapshot, Long> {

	List<ReportEvidenceSnapshot> findByGeneration_IdOrderByOccurredAtAscEvidenceIdAsc(
		Long generationId
	);
}
