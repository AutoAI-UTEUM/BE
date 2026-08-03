package io.edupilot.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.edupilot.classroom.Classroom;
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
	name = "student_reports",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_student_reports_generation",
			columnNames = "generation_id"
		),
		@UniqueConstraint(
			name = "uk_student_reports_classroom_student_version",
			columnNames = {"classroom_id", "student_id", "version"}
		)
	}
)
@Check(constraints = "version >= 1 "
	+ "AND (overall_score IS NULL OR (overall_score >= 0 AND overall_score <= 100))")
public class StudentReport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "generation_id", nullable = false)
	private ReportGeneration generation;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "classroom_id", nullable = false)
	private Classroom classroom;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_id", nullable = false)
	private User student;

	@Column(nullable = false)
	private int version;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "previous_report_id")
	private StudentReport previousReport;

	@Column(name = "overall_score", precision = 5, scale = 2)
	private BigDecimal overallScore;

	@Column(name = "overall_stage", length = 20)
	private String overallStage;

	@Column(columnDefinition = "text")
	private String summary;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "data_quality_json", nullable = false, columnDefinition = "json")
	private Map<String, Object> dataQuality;

	@Column(nullable = false, length = 100)
	private String model;

	@Column(name = "prompt_version", nullable = false, length = 20)
	private String promptVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected StudentReport() {
	}

	private StudentReport(
		ReportGeneration generation,
		Classroom classroom,
		User student,
		int version,
		StudentReport previousReport,
		BigDecimal overallScore,
		String overallStage,
		String summary,
		Map<String, Object> dataQuality,
		String model,
		String promptVersion
	) {
		this.generation = generation;
		this.classroom = classroom;
		this.student = student;
		this.version = version;
		this.previousReport = previousReport;
		this.overallScore = overallScore;
		this.overallStage = overallStage;
		this.summary = summary;
		this.dataQuality = dataQuality;
		this.model = model;
		this.promptVersion = promptVersion;
	}

	public static StudentReport create(
		ReportGeneration generation,
		Classroom classroom,
		User student,
		int version,
		StudentReport previousReport,
		BigDecimal overallScore,
		String overallStage,
		String summary,
		Map<String, Object> dataQuality,
		String model,
		String promptVersion
	) {
		return new StudentReport(
			generation,
			classroom,
			student,
			version,
			previousReport,
			overallScore,
			overallStage,
			summary,
			dataQuality,
			model,
			promptVersion
		);
	}

	public Long getId() { return id; }
	public Long getGenerationId() { return generation.getId(); }
	public Long getClassroomId() { return classroom.getId(); }
	public Long getStudentId() { return student.getId(); }
	public int getVersion() { return version; }
	public Long getPreviousReportId() {
		return previousReport == null ? null : previousReport.getId();
	}
	public BigDecimal getOverallScore() { return overallScore; }
	public String getOverallStage() { return overallStage; }
	public String getSummary() { return summary; }
	public Map<String, Object> getDataQuality() { return dataQuality; }
	public String getModel() { return model; }
	public String getPromptVersion() { return promptVersion; }
}
