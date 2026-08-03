package io.edupilot.report;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportCriterionResultRepository
	extends JpaRepository<ReportCriterionResult, Long> {

	List<ReportCriterionResult> findByReport_IdOrderByCriterionKey(Long reportId);
}
