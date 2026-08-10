package io.edupilot.quiz;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizSubmissionRepository
	extends JpaRepository<QuizSubmission, Long> {

	boolean existsByQuiz_IdAndUser_Id(Long quizId, Long userId);

	@Query("""
		select submission
		from QuizSubmission submission
		join fetch submission.quiz quiz
		join fetch quiz.session session
		where quiz.id = :quizId
		  and submission.user.id = :userId
		  and submission.requestId = :requestId
		""")
	Optional<QuizSubmission> findByRequest(
		@Param("quizId") Long quizId,
		@Param("userId") Long userId,
		@Param("requestId") String requestId
	);

	List<QuizSubmission> findByQuiz_IdInAndUser_Id(
		Collection<Long> quizIds,
		Long userId
	);

	Optional<QuizSubmission> findByIdAndUser_Id(Long id, Long userId);

	@Query("""
		select submission
		from QuizSubmission submission
		join fetch submission.quiz quiz
		join fetch quiz.session session
		where submission.user.id = :studentId
		  and session.user.id = :studentId
		  and session.status in (
		    io.edupilot.session.SessionStatus.ACTIVE,
		    io.edupilot.session.SessionStatus.COMPLETED
		  )
		  and submission.attemptNo = (
		    select max(candidate.attemptNo)
		    from QuizSubmission candidate
		    where candidate.quiz.id = quiz.id
		      and candidate.user.id = :studentId
		  )
		  and exists (
		    select link.id
		    from ClassroomWeekMaterial link
		    where link.material = session.material
		      and link.week.classroom.id = :classroomId
		      and (:weekNumber is null or link.week.weekNumber = :weekNumber)
		  )
		order by submission.createdAt, submission.id
		""")
	List<QuizSubmission> findReportSubmissions(
		@Param("classroomId") Long classroomId,
		@Param("studentId") Long studentId,
		@Param("weekNumber") Integer weekNumber
	);
}
