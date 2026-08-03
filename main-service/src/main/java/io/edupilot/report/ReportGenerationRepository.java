package io.edupilot.report;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportGenerationRepository extends JpaRepository<ReportGeneration, Long> {
}
