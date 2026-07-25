package io.edupilot.quiz;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
