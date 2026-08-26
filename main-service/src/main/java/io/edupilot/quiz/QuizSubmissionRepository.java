package io.edupilot.quiz;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.edupilot.session.SessionStatus;

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

	@Query("""
		select submission
		from QuizSubmission submission
		join fetch submission.quiz quiz
		join fetch quiz.session session
		where quiz.id = :quizId
		  and submission.user.id = :userId
		  and session.user.id = :userId
		  and session.status <> io.edupilot.session.SessionStatus.DELETED
		""")
	Optional<QuizSubmission> findOwnedByQuizId(
		@Param("quizId") Long quizId,
		@Param("userId") Long userId
	);

	List<QuizSubmission> findByQuiz_IdInAndUser_Id(
		Collection<Long> quizIds,
		Long userId
	);

	@Query("""
		select submission
		from QuizSubmission submission
		join fetch submission.quiz quiz
		join fetch quiz.session session
		where submission.user.id = :userId
		  and session.material.id = :materialId
		  and session.status <> io.edupilot.session.SessionStatus.DELETED
		order by submission.createdAt, submission.id
		""")
	List<QuizSubmission> findReviewSubmissions(
		@Param("userId") Long userId,
		@Param("materialId") Long materialId
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

	@Query("""
		select submission.quiz.id as quizId,
		       submission.score as score,
		       submission.maxScore as maxScore,
		       submission.passed as passed,
		       submission.createdAt as submittedAt
		from QuizSubmission submission
		where submission.user.id = :studentId
		  and submission.quiz.id in :quizIds
		  and submission.attemptNo = (
		    select max(candidate.attemptNo)
		    from QuizSubmission candidate
		    where candidate.quiz.id = submission.quiz.id
		      and candidate.user.id = :studentId
		  )
		order by submission.quiz.id
		""")
	List<StudentLatestQuizSubmission> findLatestByStudentAndQuizIds(
		@Param("studentId") Long studentId,
		@Param("quizIds") Collection<Long> quizIds
	);

	@Query("""
		select submission.user.id as studentId,
		       count(distinct submission.quiz.id) as submissionCount
		from QuizSubmission submission
		join submission.quiz quiz
		join quiz.session session
		where submission.user.id in :studentIds
		  and session.user.id = submission.user.id
		  and session.status in :statuses
		  and exists (
		    select link.id
		    from ClassroomWeekMaterial link
		    where link.material = session.material
		      and link.week.classroom.id = :classroomId
		  )
		group by submission.user.id
		""")
	List<StudentQuizSubmissionCount> findSubmissionCountsByStudentIds(
		@Param("classroomId") Long classroomId,
		@Param("studentIds") Collection<Long> studentIds,
		@Param("statuses") Collection<SessionStatus> statuses
	);

	interface StudentLatestQuizSubmission {
		Long getQuizId();
		BigDecimal getScore();
		BigDecimal getMaxScore();
		Boolean getPassed();
		Instant getSubmittedAt();
	}

	interface StudentQuizSubmissionCount {
		Long getStudentId();
		long getSubmissionCount();
	}
}
