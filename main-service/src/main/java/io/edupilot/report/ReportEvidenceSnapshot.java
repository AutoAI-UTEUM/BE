package io.edupilot.report;

import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

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
	name = "report_evidence_snapshots",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_report_evidence_snapshots_generation_evidence",
		columnNames = {"generation_id", "evidence_id"}
	),
	indexes = @Index(
		name = "idx_report_evidence_snapshots_generation_source",
		columnList = "generation_id, source_type"
	)
)
public class ReportEvidenceSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "generation_id", nullable = false)
	private ReportGeneration generation;

	@Column(name = "evidence_id", nullable = false, length = 64)
	private String evidenceId;

	@Column(name = "source_type", nullable = false, length = 30)
	private String sourceType;

	@Column(name = "source_ref", nullable = false, length = 255)
	private String sourceRef;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "public_label", nullable = false, length = 255)
	private String publicLabel;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "minimal_fact_json", nullable = false, columnDefinition = "json")
	private Map<String, Object> minimalFact;

	@Column(name = "source_hash", nullable = false, length = 64)
	private String sourceHash;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ReportEvidenceSnapshot() {
	}

	private ReportEvidenceSnapshot(
		ReportGeneration generation,
		String evidenceId,
		String sourceType,
		String sourceRef,
		Instant occurredAt,
		String publicLabel,
		Map<String, Object> minimalFact,
		String sourceHash
	) {
		this.generation = generation;
		this.evidenceId = evidenceId;
		this.sourceType = sourceType;
		this.sourceRef = sourceRef;
		this.occurredAt = occurredAt;
		this.publicLabel = publicLabel;
		this.minimalFact = minimalFact;
		this.sourceHash = sourceHash;
	}

	public static ReportEvidenceSnapshot create(
		ReportGeneration generation,
		String evidenceId,
		String sourceType,
		String sourceRef,
		Instant occurredAt,
		String publicLabel,
		Map<String, Object> minimalFact,
		String sourceHash
	) {
		return new ReportEvidenceSnapshot(
			generation,
			evidenceId,
			sourceType,
			sourceRef,
			occurredAt,
			publicLabel,
			minimalFact,
			sourceHash
		);
	}

	public Long getId() { return id; }
	public Long getGenerationId() { return generation.getId(); }
	public String getEvidenceId() { return evidenceId; }
	public String getSourceType() { return sourceType; }
	public String getSourceRef() { return sourceRef; }
	public Instant getOccurredAt() { return occurredAt; }
	public String getPublicLabel() { return publicLabel; }
	public Map<String, Object> getMinimalFact() { return minimalFact; }
	public String getSourceHash() { return sourceHash; }
}
