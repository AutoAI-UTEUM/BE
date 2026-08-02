package io.edupilot.exam;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	long countByExam_Id(Long examId);

	@Query("select count(distinct submission.user.id) from ExamSubmission submission "
		+ "where submission.exam.id = :examId")
	long countDistinctUsersByExamId(@Param("examId") Long examId);

	long countByExam_IdAndUser_Id(Long examId, Long userId);

	Page<ExamSubmission> findByExam_Id(Long examId, Pageable pageable);

	@Query(
		value = """
			select submission
			from ExamSubmission submission
			where submission.exam.id = :examId
			  and submission.attemptNo = (
			    select max(candidate.attemptNo)
			    from ExamSubmission candidate
			    where candidate.exam.id = submission.exam.id
			      and candidate.user.id = submission.user.id
			  )
			""",
		countQuery = """
			select count(distinct submission.user.id)
			from ExamSubmission submission
			where submission.exam.id = :examId
			"""
	)
	Page<ExamSubmission> findLatestByExamId(
		@Param("examId") Long examId,
		Pageable pageable
	);

	Optional<ExamSubmission> findByIdAndExam_Id(Long id, Long examId);
}
