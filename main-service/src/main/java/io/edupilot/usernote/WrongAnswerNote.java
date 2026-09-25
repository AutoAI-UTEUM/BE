package io.edupilot.usernote;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

@Entity
@Table(name = "wrong_answer_notes")
public class WrongAnswerNote {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "quiz_result_ref", nullable = false, length = 255)
	private String quizResultRef;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "question_snapshot", nullable = false, columnDefinition = "json")
	private WrongAnswerQuestionSnapshot questionSnapshot;

	@Column(columnDefinition = "TEXT")
	private String memo;

	@Column(name = "client_id", length = 64)
	private String clientId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected WrongAnswerNote() {
	}

	private WrongAnswerNote(
		User user, String quizResultRef, WrongAnswerQuestionSnapshot questionSnapshot,
		String memo, String clientId, Instant now
	) {
		this.user = user;
		this.quizResultRef = quizResultRef;
		this.questionSnapshot = questionSnapshot;
		this.memo = memo;
		this.clientId = clientId;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static WrongAnswerNote create(
		User user, String quizResultRef, WrongAnswerQuestionSnapshot questionSnapshot,
		String memo, String clientId, Instant now
	) {
		return new WrongAnswerNote(user, quizResultRef, questionSnapshot, memo, clientId, now);
	}

	public void updateMemo(String memo, Instant now) {
		this.memo = memo;
		this.updatedAt = now;
	}

	public void delete(Instant now) {
		this.deletedAt = now;
		this.updatedAt = now;
	}

	public Long getId() { return id; }
	public String getQuizResultRef() { return quizResultRef; }
	public WrongAnswerQuestionSnapshot getQuestionSnapshot() { return questionSnapshot; }
	public String getMemo() { return memo; }
	public String getClientId() { return clientId; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public Instant getDeletedAt() { return deletedAt; }
}
