package io.edupilot.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.edupilot.classroom.Classroom;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "report_criteria",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_report_criteria_classroom_key_version",
		columnNames = {"classroom_id", "criterion_key", "version"}
	),
	indexes = @Index(
		name = "idx_report_criteria_classroom_active",
		columnList = "classroom_id, active"
	)
)
@Check(constraints = "min_evidence >= 1 AND weight > 0 AND version >= 1")
public class ReportCriterion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "classroom_id", nullable = false)
	private Classroom classroom;

	@Column(name = "criterion_key", nullable = false, length = 50)
	private String criterionKey;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 500)
	private String description;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "rubric_json", nullable = false, columnDefinition = "json")
	private Map<String, Object> rubric;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "allowed_sources_json", nullable = false, columnDefinition = "json")
	private List<String> allowedSources;

	@Column(name = "min_evidence", nullable = false)
	private int minEvidence;

	@Column(nullable = false, precision = 5, scale = 2)
	private BigDecimal weight;

	@Column(nullable = false)
	private int version;

	@Column(nullable = false)
	private boolean active;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ReportCriterion() {
	}

	private ReportCriterion(
		Classroom classroom,
		String criterionKey,
		String name,
		String description,
		Map<String, Object> rubric,
		List<String> allowedSources,
		int minEvidence,
		BigDecimal weight,
		int version,
		boolean active
	) {
		this.classroom = classroom;
		this.criterionKey = criterionKey;
		this.name = name;
		this.description = description;
		this.rubric = rubric;
		this.allowedSources = allowedSources;
		this.minEvidence = minEvidence;
		this.weight = weight;
		this.version = version;
		this.active = active;
	}

	public static ReportCriterion create(
		Classroom classroom,
		String criterionKey,
		String name,
		String description,
		Map<String, Object> rubric,
		List<String> allowedSources,
		int minEvidence,
		BigDecimal weight,
		int version,
		boolean active
	) {
		return new ReportCriterion(
			classroom,
			criterionKey,
			name,
			description,
			rubric,
			allowedSources,
			minEvidence,
			weight,
			version,
			active
		);
	}

	public Long getId() { return id; }
	public Long getClassroomId() { return classroom.getId(); }
	public String getCriterionKey() { return criterionKey; }
	public String getName() { return name; }
	public String getDescription() { return description; }
	public Map<String, Object> getRubric() { return rubric; }
	public List<String> getAllowedSources() { return allowedSources; }
	public int getMinEvidence() { return minEvidence; }
	public BigDecimal getWeight() { return weight; }
	public int getVersion() { return version; }
	public boolean isActive() { return active; }

	public void activate() {
		this.active = true;
	}

	public void deactivate() {
		this.active = false;
	}
}
