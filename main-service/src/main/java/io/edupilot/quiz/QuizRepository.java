package io.edupilot.quiz;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.edupilot.session.SessionStatus;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

	@Query("""
		select quiz
		from Quiz quiz
		join fetch quiz.session session
		join fetch session.material
		where quiz.id = :quizId
		  and session.user.id = :userId
		""")
	Optional<Quiz> findOwned(
		@Param("quizId") Long quizId,
		@Param("userId") Long userId
	);

	@Query("""
		select quiz
		from Quiz quiz
		where quiz.id = :quizId
		  and quiz.session.id = :sessionId
		""")
	Optional<Quiz> findByIdAndSessionId(
		@Param("quizId") Long quizId,
		@Param("sessionId") Long sessionId
	);

	List<Quiz> findBySession_IdOrderByCreatedAtDescIdDesc(
		Long sessionId,
		Pageable pageable
	);

	@Query("""
		select quiz.id as quizId,
		       session.material.id as materialId,
		       quiz.title as title,
		       quiz.quizType as quizType,
		       quiz.pageNumber as pageNumber
		from Quiz quiz
		join quiz.session session
		where session.user.id = :studentId
		  and session.material.id in :materialIds
		  and session.status in :statuses
		order by quiz.createdAt, quiz.id
		""")
	List<StudentQuizSummary> findStudentQuizSummaries(
		@Param("studentId") Long studentId,
		@Param("materialIds") Collection<Long> materialIds,
		@Param("statuses") Collection<SessionStatus> statuses
	);

	interface StudentQuizSummary {
		Long getQuizId();
		Long getMaterialId();
		String getTitle();
		QuizType getQuizType();
		Integer getPageNumber();
	}
}
