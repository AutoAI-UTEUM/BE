package io.edupilot.diagnosis;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface DiagnosisRepository extends JpaRepository<Diagnosis, Long> {

	Optional<Diagnosis> findBySubmission_Id(Long submissionId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select diagnosis
		from Diagnosis diagnosis
		join fetch diagnosis.session session
		where diagnosis.id = :diagnosisId
		""")
	Optional<Diagnosis> findByIdForUpdate(
		@Param("diagnosisId") Long diagnosisId
	);
}
