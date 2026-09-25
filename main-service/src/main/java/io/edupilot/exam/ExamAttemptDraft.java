package io.edupilot.exam;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "exam_attempt_drafts",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_exam_attempt_drafts_exam_user",
		columnNames = {"exam_id", "user_id"}
	)
)
public class ExamAttemptDraft {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "exam_id", nullable = false)
	private Exam exam;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "answers", nullable = false, columnDefinition = "json")
	private List<ExamAnswerRequest> answers;

	@Column(name = "version", nullable = false)
	private int version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ExamAttemptDraft() {
	}

	private ExamAttemptDraft(
		Exam exam,
		User user,
		List<ExamAnswerRequest> answers,
		Instant now
	) {
		this.exam = exam;
		this.user = user;
		this.answers = List.copyOf(answers);
		this.version = 1;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static ExamAttemptDraft create(
		Exam exam,
		User user,
		List<ExamAnswerRequest> answers,
		Instant now
	) {
		return new ExamAttemptDraft(exam, user, answers, now);
	}

	public int getVersion() { return version; }
	public List<ExamAnswerRequest> getAnswers() { return answers; }
	public Instant getUpdatedAt() { return updatedAt; }
}
