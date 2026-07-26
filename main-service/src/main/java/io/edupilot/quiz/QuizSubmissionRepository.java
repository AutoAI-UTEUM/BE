package io.edupilot.quiz;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizSubmissionRepository
	extends JpaRepository<QuizSubmission, Long> {

	boolean existsByQuiz_IdAndUser_Id(Long quizId, Long userId);

	List<QuizSubmission> findByQuiz_IdInAndUser_Id(
		Collection<Long> quizIds,
		Long userId
	);

	Optional<QuizSubmission> findByIdAndUser_Id(Long id, Long userId);
}
