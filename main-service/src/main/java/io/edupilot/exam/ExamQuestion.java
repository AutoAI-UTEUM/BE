package io.edupilot.exam;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

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
@Table(name = "exam_questions")
public class ExamQuestion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "exam_id", nullable = false)
	private Exam exam;

	@Column(name = "question_no", nullable = false)
	private int questionNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "question_type", nullable = false, length = 20)
	private ExamQuestionType questionType;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal points;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "public_question_json", nullable = false, columnDefinition = "json")
	private ExamPublicQuestion publicQuestion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "private_answer_json", nullable = false, columnDefinition = "json")
	private ExamPrivateAnswer privateAnswer;

	@Column(name = "schema_version", nullable = false, length = 20)
	private String schemaVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ExamQuestion() {
	}

	private ExamQuestion(
		Exam exam,
		int questionNo,
		ExamQuestionType questionType,
		BigDecimal points,
		ExamPublicQuestion publicQuestion,
		ExamPrivateAnswer privateAnswer,
		String schemaVersion
	) {
		this.exam = exam;
		this.questionNo = questionNo;
		this.questionType = questionType;
		this.points = points;
		this.publicQuestion = publicQuestion;
		this.privateAnswer = privateAnswer;
		this.schemaVersion = schemaVersion;
	}

	public static ExamQuestion create(
		Exam exam,
		int questionNo,
		ExamQuestionType questionType,
		BigDecimal points,
		ExamPublicQuestion publicQuestion,
		ExamPrivateAnswer privateAnswer,
		String schemaVersion
	) {
		return new ExamQuestion(
			exam, questionNo, questionType, points, publicQuestion, privateAnswer, schemaVersion
		);
	}

	public Long getId() { return id; }
	public Long getExamId() { return exam.getId(); }
	public int getQuestionNo() { return questionNo; }
	public ExamQuestionType getQuestionType() { return questionType; }
	public BigDecimal getPoints() { return points; }
	public ExamPublicQuestion getPublicQuestion() { return publicQuestion; }
	public ExamPrivateAnswer getPrivateAnswer() { return privateAnswer; }
	public String getSchemaVersion() { return schemaVersion; }
}
