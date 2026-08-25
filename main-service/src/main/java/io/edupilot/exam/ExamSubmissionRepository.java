package io.edupilot.exam;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

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

	Optional<ExamSubmission> findByExam_IdAndUser_IdAndAttemptNo(
		Long examId,
		Long userId,
		int attemptNo
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

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update ExamSubmission submission "
		+ "set submission.gradingLeaseToken = :leaseToken, "
		+ "submission.gradingLeaseUntil = :leaseUntil, "
		+ "submission.updatedAt = :now "
		+ "where submission.id = :submissionId "
		+ "and submission.status = io.edupilot.exam.SubmissionStatus.SUBMITTED "
		+ "and submission.gradingLeaseUntil < :now")
	int claimGradingLease(
		@Param("submissionId") Long submissionId,
		@Param("leaseToken") String leaseToken,
		@Param("now") Instant now,
		@Param("leaseUntil") Instant leaseUntil
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select submission from ExamSubmission submission where submission.id = :submissionId")
	Optional<ExamSubmission> findByIdForUpdate(@Param("submissionId") Long submissionId);

	@Query("select new io.edupilot.exam.ExamGradingCandidate("
		+ "submission.id, submission.exam.id) "
		+ "from ExamSubmission submission "
		+ "where submission.status = io.edupilot.exam.SubmissionStatus.SUBMITTED "
		+ "and submission.updatedAt <= :cutoff "
		+ "and submission.gradingRetryCount < :requeueLimit "
		+ "order by submission.updatedAt, submission.id")
	List<ExamGradingCandidate> findRetryableExpiredSubmissions(
		@Param("cutoff") Instant cutoff,
		@Param("requeueLimit") int requeueLimit,
		Pageable pageable
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update ExamSubmission submission "
		+ "set submission.gradingRetryCount = submission.gradingRetryCount + 1, "
		+ "submission.gradingLeaseToken = null, "
		+ "submission.gradingLeaseUntil = :noLease, submission.updatedAt = :now "
		+ "where submission.id in :submissionIds "
		+ "and submission.status = io.edupilot.exam.SubmissionStatus.SUBMITTED "
		+ "and submission.updatedAt <= :cutoff "
		+ "and submission.gradingRetryCount < :requeueLimit")
	int requeueExpiredSubmissions(
		@Param("submissionIds") List<Long> submissionIds,
		@Param("cutoff") Instant cutoff,
		@Param("requeueLimit") int requeueLimit,
		@Param("noLease") Instant noLease,
		@Param("now") Instant now
	);

	@Query("select submission.id from ExamSubmission submission "
		+ "where submission.status = io.edupilot.exam.SubmissionStatus.SUBMITTED "
		+ "and submission.updatedAt <= :cutoff "
		+ "and submission.gradingRetryCount >= :requeueLimit "
		+ "order by submission.updatedAt, submission.id")
	List<Long> findExhaustedSubmissionIds(
		@Param("cutoff") Instant cutoff,
		@Param("requeueLimit") int requeueLimit,
		Pageable pageable
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update ExamSubmission submission "
		+ "set submission.status = io.edupilot.exam.SubmissionStatus.GRADING_FAILED, "
		+ "submission.gradingRetryCount = :maxRetries, "
		+ "submission.score = null, submission.normalizedScore = null, "
		+ "submission.gradedAt = null, submission.gradingLeaseToken = null, "
		+ "submission.gradingLeaseUntil = :noLease, submission.updatedAt = :now "
		+ "where submission.id in :submissionIds "
		+ "and submission.status = io.edupilot.exam.SubmissionStatus.SUBMITTED "
		+ "and submission.updatedAt <= :cutoff "
		+ "and submission.gradingRetryCount >= :requeueLimit")
	int failExhaustedSubmissions(
		@Param("submissionIds") List<Long> submissionIds,
		@Param("cutoff") Instant cutoff,
		@Param("requeueLimit") int requeueLimit,
		@Param("maxRetries") int maxRetries,
		@Param("noLease") Instant noLease,
		@Param("now") Instant now
	);

	@Query("select submission from ExamSubmission submission "
		+ "where submission.status = io.edupilot.exam.SubmissionStatus.SUBMITTED "
		+ "and submission.updatedAt > :cutoff "
		+ "and submission.gradingLeaseUntil < :now "
		+ "order by submission.updatedAt, submission.id")
	List<ExamSubmission> findRecoverableSubmissions(
		@Param("cutoff") Instant cutoff,
		@Param("now") Instant now,
		Pageable pageable
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update ExamSubmission submission "
		+ "set submission.status = io.edupilot.exam.SubmissionStatus.SUBMITTED, "
		+ "submission.score = null, submission.normalizedScore = null, "
		+ "submission.gradedAt = null, submission.gradingRetryCount = 0, "
		+ "submission.gradingLeaseToken = null, "
		+ "submission.gradingLeaseUntil = :noLease, submission.updatedAt = :now "
		+ "where submission.id = :submissionId "
		+ "and submission.exam.id = :examId "
		+ "and submission.status = io.edupilot.exam.SubmissionStatus.GRADING_FAILED")
	int requeueFailedSubmission(
		@Param("examId") Long examId,
		@Param("submissionId") Long submissionId,
		@Param("noLease") Instant noLease,
		@Param("now") Instant now
	);

	@Query("""
		select submission
		from ExamSubmission submission
		join fetch submission.exam exam
		where exam.classroom.id = :classroomId
		  and submission.user.id = :studentId
		  and (:weekNumber is null or exam.weekNumber = :weekNumber)
		  and submission.status = io.edupilot.exam.SubmissionStatus.GRADED
		  and submission.attemptNo = (
		    select max(candidate.attemptNo)
		    from ExamSubmission candidate
		    where candidate.exam.id = exam.id
		      and candidate.user.id = :studentId
		      and candidate.status = io.edupilot.exam.SubmissionStatus.GRADED
		  )
		order by submission.gradedAt, submission.id
		""")
	List<ExamSubmission> findRepresentativeReportSubmissions(
		@Param("classroomId") Long classroomId,
		@Param("studentId") Long studentId,
		@Param("weekNumber") Integer weekNumber
	);
}
