package io.edupilot.exam;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.edupilot.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "exam_submissions")
public class ExamSubmission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "exam_id", nullable = false)
	private Exam exam;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "attempt_no", nullable = false)
	private int attemptNo;

	@Column(name = "request_id", nullable = false, length = 255)
	private String requestId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SubmissionStatus status;

	@Column(name = "submitted_at", nullable = false)
	private Instant submittedAt;

	@Column(name = "graded_at")
	private Instant gradedAt;

	@Column(precision = 10, scale = 2)
	private BigDecimal score;

	@Column(name = "max_score", nullable = false, precision = 10, scale = 2)
	private BigDecimal maxScore;

	@Column(name = "normalized_score", precision = 10, scale = 2)
	private BigDecimal normalizedScore;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ExamSubmission() {
	}

	private ExamSubmission(
		Exam exam,
		User user,
		int attemptNo,
		String requestId,
		BigDecimal maxScore,
		Instant submittedAt
	) {
		this.exam = exam;
		this.user = user;
		this.attemptNo = attemptNo;
		this.requestId = requestId;
		this.status = SubmissionStatus.SUBMITTED;
		this.maxScore = maxScore;
		this.submittedAt = submittedAt;
	}

	public static ExamSubmission create(
		Exam exam,
		User user,
		int attemptNo,
		String requestId,
		BigDecimal maxScore,
		Instant submittedAt
	) {
		return new ExamSubmission(exam, user, attemptNo, requestId, maxScore, submittedAt);
	}

	public Long getId() { return id; }
	public Long getExamId() { return exam.getId(); }
	public Long getUserId() { return user.getId(); }
	public int getAttemptNo() { return attemptNo; }
	public String getRequestId() { return requestId; }
	public SubmissionStatus getStatus() { return status; }
	public Instant getSubmittedAt() { return submittedAt; }
	public Instant getGradedAt() { return gradedAt; }
	public BigDecimal getScore() { return score; }
	public BigDecimal getMaxScore() { return maxScore; }
	public BigDecimal getNormalizedScore() { return normalizedScore; }
}
