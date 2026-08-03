package io.edupilot.report;

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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
	name = "report_generations",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_report_generations_classroom_student_request",
		columnNames = {"classroom_id", "student_id", "request_id"}
	),
	indexes = {
		@Index(
			name = "idx_report_generations_status_lease",
			columnList = "status, generation_lease_until"
		),
		@Index(
			name = "idx_report_generations_classroom_student_status",
			columnList = "classroom_id, student_id, status"
		)
	}
)
@Check(constraints = "scope_type IN ('FULL', 'WEEK') "
	+ "AND (week_number IS NULL OR week_number >= 1) "
	+ "AND status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')")
public class ReportGeneration {

	private static final Instant NO_GENERATION_LEASE = Instant.EPOCH;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "classroom_id", nullable = false)
	private Classroom classroom;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_id", nullable = false)
	private User student;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requested_by", nullable = false)
	private User requestedBy;

	@Column(name = "request_id", nullable = false, length = 255)
	private String requestId;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope_type", nullable = false, length = 20)
	private ReportScopeType scopeType;

	@Column(name = "week_number")
	private Integer weekNumber;

	@Column(name = "scope_hash", nullable = false, length = 64)
	private String scopeHash;

	@Column(name = "snapshot_hash", length = 64)
	private String snapshotHash;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "criterion_catalog_json", columnDefinition = "json")
	private Map<String, Object> generationInput;

	@Column(name = "policy_version", nullable = false, length = 20)
	private String policyVersion;

	@Column(name = "source_data_as_of")
	private Instant sourceDataAsOf;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReportGenerationStatus status;

	@Column(name = "failure_code", length = 50)
	private String failureCode;

	@Column(length = 100)
	private String model;

	@Column(name = "prompt_version", length = 20)
	private String promptVersion;

	@Column(name = "generation_lease_token", length = 36)
	private String generationLeaseToken;

	@Column(name = "generation_lease_until", nullable = false)
	private Instant generationLeaseUntil = NO_GENERATION_LEASE;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ReportGeneration() {
	}

	private ReportGeneration(
		Classroom classroom,
		User student,
		User requestedBy,
		String requestId,
		ReportScopeType scopeType,
		Integer weekNumber,
		String scopeHash,
		String policyVersion
	) {
		this.classroom = classroom;
		this.student = student;
		this.requestedBy = requestedBy;
		this.requestId = requestId;
		this.scopeType = scopeType;
		this.weekNumber = weekNumber;
		this.scopeHash = scopeHash;
		this.policyVersion = policyVersion;
		this.status = ReportGenerationStatus.PENDING;
		this.generationLeaseUntil = NO_GENERATION_LEASE;
	}

	public static ReportGeneration create(
		Classroom classroom,
		User student,
		User requestedBy,
		String requestId,
		ReportScopeType scopeType,
		Integer weekNumber,
		String scopeHash,
		String policyVersion
	) {
		return new ReportGeneration(
			classroom,
			student,
			requestedBy,
			requestId,
			scopeType,
			weekNumber,
			scopeHash,
			policyVersion
		);
	}

	public void start() {
		this.status = ReportGenerationStatus.PROCESSING;
		this.failureCode = null;
	}

	public void freezeSnapshot(
		String snapshotHash,
		Map<String, Object> generationInput,
		Instant sourceDataAsOf
	) {
		if (this.snapshotHash != null || this.generationInput != null) {
			throw new IllegalStateException("Report snapshot is already frozen");
		}
		this.snapshotHash = snapshotHash;
		this.generationInput = Map.copyOf(generationInput);
		this.sourceDataAsOf = sourceDataAsOf;
	}

	public void complete() {
		this.status = ReportGenerationStatus.COMPLETED;
		this.failureCode = null;
		clearGenerationLease();
	}

	public void fail(String failureCode) {
		this.status = ReportGenerationStatus.FAILED;
		this.failureCode = failureCode;
		clearGenerationLease();
	}

	private void clearGenerationLease() {
		this.generationLeaseToken = null;
		this.generationLeaseUntil = NO_GENERATION_LEASE;
	}

	public Long getId() { return id; }
	public Long getClassroomId() { return classroom.getId(); }
	public Long getStudentId() { return student.getId(); }
	public Long getRequestedById() { return requestedBy.getId(); }
	public String getRequestId() { return requestId; }
	public ReportScopeType getScopeType() { return scopeType; }
	public Integer getWeekNumber() { return weekNumber; }
	public String getScopeHash() { return scopeHash; }
	public String getSnapshotHash() { return snapshotHash; }
	public Map<String, Object> getGenerationInput() { return generationInput; }
	public String getPolicyVersion() { return policyVersion; }
	public Instant getSourceDataAsOf() { return sourceDataAsOf; }
	public ReportGenerationStatus getStatus() { return status; }
	public String getFailureCode() { return failureCode; }
	public String getModel() { return model; }
	public String getPromptVersion() { return promptVersion; }
	public String getGenerationLeaseToken() { return generationLeaseToken; }
	public Instant getGenerationLeaseUntil() { return generationLeaseUntil; }
}
