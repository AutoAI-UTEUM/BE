package io.edupilot.assessment;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizAssessmentRepository
	extends JpaRepository<QuizAssessment, Long> {

	Optional<QuizAssessment> findBySubmission_Id(Long submissionId);

	List<QuizAssessment> findTop5BySession_IdOrderByCreatedAtDescIdDesc(
		Long sessionId
	);

	@Query("""
		select assessment
		from QuizAssessment assessment
		join assessment.submission submission
		join submission.quiz quiz
		join quiz.session session
		where submission.user.id = :userId
		  and session.material.id = :materialId
		order by assessment.createdAt desc, assessment.id desc
		""")
	List<QuizAssessment> findRecentByUserAndMaterial(
		@Param("userId") Long userId,
		@Param("materialId") Long materialId,
		Pageable pageable
	);

	@Query("""
		select assessment
		from QuizAssessment assessment
		join fetch assessment.submission submission
		join submission.quiz quiz
		join quiz.session session
		where assessment.submission.id in :submissionIds
		  and submission.user.id = :studentId
		  and session.user.id = :studentId
		  and exists (
		    select link.id
		    from ClassroomWeekMaterial link
		    where link.material = session.material
		      and link.week.classroom.id = :classroomId
		      and (:weekNumber is null or link.week.weekNumber = :weekNumber)
		  )
		order by assessment.createdAt, assessment.id
		""")
	List<QuizAssessment> findReportAssessments(
		@Param("classroomId") Long classroomId,
		@Param("studentId") Long studentId,
		@Param("weekNumber") Integer weekNumber,
		@Param("submissionIds") Collection<Long> submissionIds
	);
}
