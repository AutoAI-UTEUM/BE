package io.edupilot.exam;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.exam.dto.ExamAnswerRequest;

public interface ExamAttemptDraftRepository extends JpaRepository<ExamAttemptDraft, Long> {

	Optional<ExamAttemptDraft> findByExam_IdAndUser_Id(Long examId, Long userId);

	@Transactional
	@Modifying
	@Query("update ExamAttemptDraft draft set draft.answers = :answers, "
		+ "draft.version = draft.version + 1, draft.updatedAt = :now "
		+ "where draft.exam.id = :examId and draft.user.id = :userId "
		+ "and draft.version = :version")
	int updateIfVersionMatches(
		@Param("examId") Long examId,
		@Param("userId") Long userId,
		@Param("version") int version,
		@Param("answers") List<ExamAnswerRequest> answers,
		@Param("now") Instant now
	);

	@Modifying
	@Query("delete from ExamAttemptDraft draft "
		+ "where draft.exam.id = :examId and draft.user.id = :userId")
	int deleteByExamAndUser(@Param("examId") Long examId, @Param("userId") Long userId);

	@Transactional
	@Modifying
	@Query("delete from ExamAttemptDraft draft where draft.updatedAt <= :cutoff")
	int deleteUpdatedBefore(@Param("cutoff") Instant cutoff);
}
