package io.edupilot.exam;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
@Table(name = "exam_answers")
public class ExamAnswer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "submission_id", nullable = false)
	private ExamSubmission submission;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "question_id", nullable = false)
	private ExamQuestion question;

	@Column(columnDefinition = "text")
	private String answer;

	@Column(precision = 10, scale = 2)
	private BigDecimal score;

	@Column(name = "manual_score", precision = 10, scale = 2)
	private BigDecimal manualScore;

	@Column(name = "adjusted_by")
	private Long adjustedBy;

	@Column(name = "adjusted_at")
	private Instant adjustedAt;

	@Column(name = "max_score", nullable = false, precision = 10, scale = 2)
	private BigDecimal maxScore;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private Verdict verdict;

	@Column(columnDefinition = "text")
	private String feedback;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ExamAnswer() {
	}

	private ExamAnswer(
		ExamSubmission submission,
		ExamQuestion question,
		String answer,
		BigDecimal maxScore
	) {
		this.submission = submission;
		this.question = question;
		this.answer = answer;
		this.maxScore = maxScore;
	}

	public static ExamAnswer create(
		ExamSubmission submission,
		ExamQuestion question,
		String answer,
		BigDecimal maxScore
	) {
		return new ExamAnswer(submission, question, answer, maxScore);
	}

	public void recordGrade(BigDecimal score, Verdict verdict, String feedback) {
		this.score = score;
		this.verdict = verdict;
		this.feedback = feedback;
	}

	public void recordManualScore(
		BigDecimal manualScore,
		Long adjustedBy,
		Instant adjustedAt
	) {
		this.manualScore = manualScore;
		this.adjustedBy = adjustedBy;
		this.adjustedAt = adjustedAt;
		this.verdict = Verdict.fromScore(manualScore, maxScore);
	}

	public BigDecimal effectiveScore() {
		return manualScore != null ? manualScore : score;
	}

	public Long getId() { return id; }
	public Long getSubmissionId() { return submission.getId(); }
	public Long getQuestionId() { return question.getId(); }
	public int getQuestionNo() { return question.getQuestionNo(); }
	public ExamQuestionType getQuestionType() { return question.getQuestionType(); }
	public String getQuestionText() { return question.getPublicQuestion().question(); }
	public ExamPublicQuestion getPublicQuestion() { return question.getPublicQuestion(); }
	public ExamPrivateAnswer getPrivateAnswer() { return question.getPrivateAnswer(); }
	public String getAnswer() { return answer; }
	public BigDecimal getScore() { return score; }
	public BigDecimal getManualScore() { return manualScore; }
	public Long getAdjustedBy() { return adjustedBy; }
	public Instant getAdjustedAt() { return adjustedAt; }
	public BigDecimal getMaxScore() { return maxScore; }
	public Verdict getVerdict() { return verdict; }
	public String getFeedback() { return feedback; }
}
