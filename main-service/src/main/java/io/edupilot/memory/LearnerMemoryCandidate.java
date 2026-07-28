package io.edupilot.memory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.edupilot.material.LearningMaterial;
import io.edupilot.user.User;
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
@Table(name = "learner_memory_candidates")
public class LearnerMemoryCandidate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "material_id", nullable = false)
	private LearningMaterial material;

	@Column(name = "candidate_type", nullable = false, length = 30)
	private String candidateType;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;

	@Column(nullable = false, precision = 3, scale = 2)
	private BigDecimal confidence;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence_refs_json", nullable = false, columnDefinition = "json")
	private List<MemoryEvidenceRef> evidenceRefs;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MemoryCandidateStatus status;

	@Column(name = "schema_version", nullable = false, length = 20)
	private String schemaVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected LearnerMemoryCandidate() {
	}

	private LearnerMemoryCandidate(
		User user,
		LearningMaterial material,
		String candidateType,
		String content,
		BigDecimal confidence,
		List<MemoryEvidenceRef> evidenceRefs,
		String schemaVersion
	) {
		this.user = user;
		this.material = material;
		this.candidateType = candidateType;
		this.content = content;
		this.confidence = confidence;
		this.evidenceRefs = List.copyOf(evidenceRefs);
		this.status = MemoryCandidateStatus.CANDIDATE;
		this.schemaVersion = schemaVersion;
	}

	public static LearnerMemoryCandidate create(
		User user,
		LearningMaterial material,
		String candidateType,
		String content,
		BigDecimal confidence,
		List<MemoryEvidenceRef> evidenceRefs,
		String schemaVersion
	) {
		return new LearnerMemoryCandidate(
			user,
			material,
			candidateType,
			content,
			confidence,
			evidenceRefs,
			schemaVersion
		);
	}

	public void promote() {
		this.status = MemoryCandidateStatus.PROMOTED;
	}

	public Long getId() {
		return id;
	}

	public String getCandidateType() {
		return candidateType;
	}

	public String getContent() {
		return content;
	}

	public BigDecimal getConfidence() {
		return confidence;
	}

	public List<MemoryEvidenceRef> getEvidenceRefs() {
		return List.copyOf(evidenceRefs);
	}

	public MemoryCandidateStatus getStatus() {
		return status;
	}
}
