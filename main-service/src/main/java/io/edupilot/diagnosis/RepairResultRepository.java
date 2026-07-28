package io.edupilot.diagnosis;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepairResultRepository
	extends JpaRepository<RepairResult, Long> {

	boolean existsByDiagnosis_Id(Long diagnosisId);

	Optional<RepairResult> findTopBySession_IdOrderByCreatedAtDescIdDesc(
		Long sessionId
	);
}
