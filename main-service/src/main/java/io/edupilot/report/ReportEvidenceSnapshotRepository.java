package io.edupilot.report;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportEvidenceSnapshotRepository
	extends JpaRepository<ReportEvidenceSnapshot, Long> {
}
