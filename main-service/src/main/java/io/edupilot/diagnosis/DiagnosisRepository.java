package io.edupilot.diagnosis;

import java.util.Collection;
import java.util.List;
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

	@Query("""
		select diagnosis
		from Diagnosis diagnosis
		join diagnosis.session session
		join diagnosis.submission submission
		where diagnosis.submission.id in :submissionIds
		  and submission.user.id = :studentId
		  and session.user.id = :studentId
		  and exists (
		    select link.id
		    from ClassroomWeekMaterial link
		    where link.material = session.material
		      and link.week.classroom.id = :classroomId
		      and (:weekNumber is null or link.week.weekNumber = :weekNumber)
		  )
		order by diagnosis.createdAt, diagnosis.id
		""")
	List<Diagnosis> findReportDiagnoses(
		@Param("classroomId") Long classroomId,
		@Param("studentId") Long studentId,
		@Param("weekNumber") Integer weekNumber,
		@Param("submissionIds") Collection<Long> submissionIds
	);
}
