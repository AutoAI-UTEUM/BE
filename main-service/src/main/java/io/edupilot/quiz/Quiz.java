package io.edupilot.quiz;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.edupilot.session.LearningSession;
import io.edupilot.session.SessionStatus;
import io.edupilot.session.UiAction;
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
@Table(name = "quizzes")
public class Quiz {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private LearningSession session;

	@Column(name = "page_number", nullable = false)
	private int pageNumber;

	@Column(nullable = false, length = 255)
	private String title;

	@Column(name = "coverage_start_page", nullable = false)
	private int coverageStartPage;

	@Column(name = "coverage_end_page", nullable = false)
	private int coverageEndPage;

	@Enumerated(EnumType.STRING)
	@Column(name = "quiz_type", nullable = false, length = 20)
	private QuizType quizType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "public_question_json", nullable = false, columnDefinition = "json")
	private QuizPublicData publicQuestionData;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "private_answer_json", nullable = false, columnDefinition = "json")
	private QuizPrivateData privateAnswerData;

	@Column(name = "schema_version", nullable = false, length = 20)
	private String schemaVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Quiz() {
	}

	private Quiz(
		LearningSession session,
		int pageNumber,
		String title,
		int coverageStartPage,
		int coverageEndPage,
		QuizType quizType,
		QuizPublicData publicQuestionData,
		QuizPrivateData privateAnswerData,
		String schemaVersion
	) {
		this.session = session;
		this.pageNumber = pageNumber;
		this.title = title;
		this.coverageStartPage = coverageStartPage;
		this.coverageEndPage = coverageEndPage;
		this.quizType = quizType;
		this.publicQuestionData = publicQuestionData;
		this.privateAnswerData = privateAnswerData;
		this.schemaVersion = schemaVersion;
	}

	public static Quiz create(
		LearningSession session,
		int pageNumber,
		String title,
		int coverageStartPage,
		int coverageEndPage,
		QuizType quizType,
		List<PublicQuizQuestion> publicQuestions,
		List<PrivateQuizQuestion> privateQuestions,
		String schemaVersion
	) {
		return new Quiz(
			session,
			pageNumber,
			title,
			coverageStartPage,
			coverageEndPage,
			quizType,
			new QuizPublicData(schemaVersion, List.copyOf(publicQuestions)),
			new QuizPrivateData(schemaVersion, List.copyOf(privateQuestions)),
			schemaVersion
		);
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

	public Long getMaterialId() {
		return session.getMaterialId();
	}

	public Long getSessionActiveQuizId() {
		return session.getActiveQuizId();
	}

	Long getSessionPendingDiagnosisId() {
		return session.getPendingDiagnosisId();
	}

	List<UiAction> getSessionUiActions() {
		return session.getLastUiActions();
	}

	public SessionStatus getSessionStatus() {
		return session.getStatus();
	}

	public int getPageNumber() {
		return pageNumber;
	}

	public String getTitle() {
		return title;
	}

	public int getCoverageStartPage() {
		return coverageStartPage;
	}

	public int getCoverageEndPage() {
		return coverageEndPage;
	}

	public QuizType getQuizType() {
		return quizType;
	}

	public List<PublicQuizQuestion> getPublicQuestions() {
		return List.copyOf(publicQuestionData.questions());
	}

	List<PrivateQuizQuestion> getPrivateQuestions() {
		return List.copyOf(privateAnswerData.questions());
	}

	public String getSchemaVersion() {
		return schemaVersion;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
