package io.edupilot.quiz;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.edupilot.session.UiAction;
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
@Table(name = "quiz_submissions")
public class QuizSubmission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "quiz_id", nullable = false)
	private Quiz quiz;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "attempt_no", nullable = false)
	private int attemptNo;

	@Column(name = "request_id", nullable = false, length = 255)
	private String requestId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "submitted_answer_json", nullable = false, columnDefinition = "json")
	private SubmittedAnswerData submittedAnswerData;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal score;

	@Column(name = "max_score", nullable = false, precision = 10, scale = 2)
	private BigDecimal maxScore;

	@Column(nullable = false)
	private boolean passed;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "grading_result_json", nullable = false, columnDefinition = "json")
	private GradingResult gradingResult;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected QuizSubmission() {
	}

	private QuizSubmission(
		Quiz quiz,
		User user,
		String requestId,
		List<SubmittedAnswer> answers,
		GradingResult gradingResult,
		boolean passed
	) {
		this.quiz = quiz;
		this.user = user;
		this.attemptNo = 1;
		this.requestId = requestId;
		this.submittedAnswerData = new SubmittedAnswerData(
			quiz.getSchemaVersion(),
			List.copyOf(answers)
		);
		this.score = gradingResult.score();
		this.maxScore = gradingResult.maxScore();
		this.passed = passed;
		this.gradingResult = gradingResult;
	}

	public static QuizSubmission create(
		Quiz quiz,
		User user,
		String requestId,
		List<SubmittedAnswer> answers,
		GradingResult gradingResult,
		boolean passed
	) {
		return new QuizSubmission(
			quiz,
			user,
			requestId,
			answers,
			gradingResult,
			passed
		);
	}

	public Long getId() {
		return id;
	}

	public Long getQuizId() {
		return quiz.getId();
	}

	Quiz getQuiz() {
		return quiz;
	}

	public int getQuizPageNumber() {
		return quiz.getPageNumber();
	}

	public Long getSessionId() {
		return quiz.getSessionId();
	}

	public Long getSessionPendingDiagnosisId() {
		return quiz.getSessionPendingDiagnosisId();
	}

	public List<UiAction> getSessionUiActions() {
		return quiz.getSessionUiActions();
	}

	public Long getUserId() {
		return user.getId();
	}

	public int getAttemptNo() {
		return attemptNo;
	}

	public QuizType getQuizType() {
		return quiz.getQuizType();
	}

	public BigDecimal getScore() {
		return score;
	}

	public BigDecimal getMaxScore() {
		return maxScore;
	}

	public boolean isPassed() {
		return passed;
	}

	public GradingResult getGradingResult() {
		return gradingResult;
	}

	List<SubmittedAnswer> getSubmittedAnswers() {
		return List.copyOf(submittedAnswerData.answers());
	}

	List<PrivateQuizQuestion> getPrivateQuestions() {
		return quiz.getPrivateQuestions();
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
