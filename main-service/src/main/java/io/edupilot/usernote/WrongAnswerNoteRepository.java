package io.edupilot.usernote;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WrongAnswerNoteRepository extends JpaRepository<WrongAnswerNote, Long> {

	Page<WrongAnswerNote> findByUser_IdAndDeletedAtIsNull(Long userId, Pageable pageable);

	Optional<WrongAnswerNote> findByIdAndUser_IdAndDeletedAtIsNull(Long id, Long userId);

	Optional<WrongAnswerNote> findByUser_IdAndClientId(Long userId, String clientId);

	Optional<WrongAnswerNote> findByUser_IdAndQuizResultRef(Long userId, String quizResultRef);
}
