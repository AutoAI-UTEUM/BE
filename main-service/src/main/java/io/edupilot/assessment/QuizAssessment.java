package io.edupilot.assessment;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.edupilot.quiz.QuizSubmission;
import io.edupilot.session.LearningSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "quiz_assessments")
public class QuizAssessment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private LearningSession session;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "quiz_submission_id", nullable = false)
	private QuizSubmission submission;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "assessment_json", nullable = false, columnDefinition = "json")
	private QuizAssessmentData assessment;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected QuizAssessment() {
	}

	private QuizAssessment(
		LearningSession session,
		QuizSubmission submission,
		QuizAssessmentData assessment
	) {
		this.session = session;
		this.submission = submission;
		this.assessment = assessment;
	}

	public static QuizAssessment create(
		LearningSession session,
		QuizSubmission submission,
		QuizAssessmentData assessment
	) {
		return new QuizAssessment(session, submission, assessment);
	}

	public Long getId() {
		return id;
	}

	public Long getSessionId() {
		return session.getId();
	}

	public Long getSubmissionId() {
		return submission.getId();
	}

	public QuizAssessmentData getAssessment() {
		return assessment;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
