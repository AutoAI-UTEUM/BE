package io.edupilot.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.Check;
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
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "report_criterion_results",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_report_criterion_results_report_key",
		columnNames = {"report_id", "criterion_key"}
	)
)
@Check(constraints = "(score IS NULL OR (score >= 0 AND score <= 100)) "
	+ "AND (trend IS NULL OR trend IN ('UP', 'FLAT', 'DOWN')) "
	+ "AND status IN ('ASSESSED', 'INSUFFICIENT_DATA') "
	+ "AND (status <> 'ASSESSED' OR score IS NOT NULL)")
public class ReportCriterionResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_id", nullable = false)
	private StudentReport report;

	@Column(name = "criterion_key", nullable = false, length = 50)
	private String criterionKey;

	@Column(name = "criterion_version", nullable = false)
	private int criterionVersion;

	@Column(precision = 5, scale = 2)
	private BigDecimal score;

	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private ReportTrend trend;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ReportCriterionStatus status;

	@Column(columnDefinition = "text")
	private String narrative;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence_ids_json", nullable = false, columnDefinition = "json")
	private List<String> evidenceIds;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ReportCriterionResult() {
	}

	private ReportCriterionResult(
		StudentReport report,
		String criterionKey,
		int criterionVersion,
		BigDecimal score,
		ReportTrend trend,
		ReportCriterionStatus status,
		String narrative,
		List<String> evidenceIds
	) {
		this.report = report;
		this.criterionKey = criterionKey;
		this.criterionVersion = criterionVersion;
		this.score = score;
		this.trend = trend;
		this.status = status;
		this.narrative = narrative;
		this.evidenceIds = evidenceIds;
	}

	public static ReportCriterionResult create(
		StudentReport report,
		String criterionKey,
		int criterionVersion,
		BigDecimal score,
		ReportTrend trend,
		ReportCriterionStatus status,
		String narrative,
		List<String> evidenceIds
	) {
		return new ReportCriterionResult(
			report,
			criterionKey,
			criterionVersion,
			score,
			trend,
			status,
			narrative,
			evidenceIds
		);
	}

	public Long getId() { return id; }
	public Long getReportId() { return report.getId(); }
	public String getCriterionKey() { return criterionKey; }
	public int getCriterionVersion() { return criterionVersion; }
	public BigDecimal getScore() { return score; }
	public ReportTrend getTrend() { return trend; }
	public ReportCriterionStatus getStatus() { return status; }
	public String getNarrative() { return narrative; }
	public List<String> getEvidenceIds() { return evidenceIds; }
}
