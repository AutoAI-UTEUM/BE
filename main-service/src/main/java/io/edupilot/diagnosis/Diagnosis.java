package io.edupilot.diagnosis;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.edupilot.quiz.QuizSubmission;
import io.edupilot.session.LearningSession;
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
@Table(name = "diagnoses")
public class Diagnosis {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private LearningSession session;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "quiz_submission_id", nullable = false)
	private QuizSubmission submission;

	@Column(name = "diagnostic_prompt", nullable = false, columnDefinition = "TEXT")
	private String diagnosticPrompt;

	@Column(name = "user_answer", columnDefinition = "TEXT")
	private String userAnswer;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "diagnosis_result_json", nullable = false, columnDefinition = "json")
	private DiagnosisData diagnosisResult;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DiagnosisStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Diagnosis() {
	}

	private Diagnosis(
		LearningSession session,
		QuizSubmission submission,
		String diagnosticPrompt,
		DiagnosisData diagnosisResult
	) {
		this.session = session;
		this.submission = submission;
		this.diagnosticPrompt = diagnosticPrompt;
		this.diagnosisResult = diagnosisResult;
		this.status = DiagnosisStatus.PENDING;
	}

	public static Diagnosis pending(
		LearningSession session,
		QuizSubmission submission,
		String diagnosticPrompt,
		DiagnosisData diagnosisResult
	) {
		return new Diagnosis(
			session,
			submission,
			diagnosticPrompt,
			diagnosisResult
		);
	}

	public void answer(String answer) {
		if (status != DiagnosisStatus.PENDING) {
			throw new IllegalStateException("Diagnosis is not pending");
		}
		this.userAnswer = answer;
		this.status = DiagnosisStatus.ANSWERED;
	}

	public void complete() {
		if (status != DiagnosisStatus.ANSWERED) {
			throw new IllegalStateException("Diagnosis is not answered");
		}
		this.status = DiagnosisStatus.COMPLETED;
	}

	public Long getId() {
		return id;
	}

	public Long getSessionId() {
		return session.getId();
	}

	public Long getUserId() {
		return session.getUserId();
	}

	public Long getSubmissionId() {
		return submission.getId();
	}

	public String getDiagnosticPrompt() {
		return diagnosticPrompt;
	}

	public String getUserAnswer() {
		return userAnswer;
	}

	public DiagnosisData getDiagnosisResult() {
		return diagnosisResult;
	}

	public DiagnosisStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
