package io.edupilot.assessment;

import java.util.List;
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
}
