package io.edupilot.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepairResultRepository
	extends JpaRepository<RepairResult, Long> {

	boolean existsByDiagnosis_Id(Long diagnosisId);
}
