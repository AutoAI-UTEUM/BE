package io.edupilot.exam;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamSubmissionRepository extends JpaRepository<ExamSubmission, Long> {

	Optional<ExamSubmission> findByExam_IdAndUser_IdAndRequestId(
		Long examId,
		Long userId,
		String requestId
	);

	Optional<ExamSubmission> findTopByExam_IdAndUser_IdOrderByAttemptNoDesc(
		Long examId,
		Long userId
	);

	boolean existsByExam_IdAndUser_Id(Long examId, Long userId);
}
