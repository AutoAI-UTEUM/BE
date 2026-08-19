package io.edupilot.material;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.edupilot.ai.dto.OutlineResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "material_overviews",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_material_overviews_material",
		columnNames = "material_id"
	)
)
public class MaterialOverview {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "material_id", nullable = false)
	private LearningMaterial material;

	@Column(columnDefinition = "MEDIUMTEXT")
	private String content;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "outline_json", columnDefinition = "json")
	private OutlineResponse outline;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MaterialOverviewStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected MaterialOverview() {
	}

	private MaterialOverview(LearningMaterial material) {
		this.material = material;
		this.content = null;
		this.outline = null;
		this.status = MaterialOverviewStatus.PENDING;
	}

	public static MaterialOverview createPending(LearningMaterial material) {
		return new MaterialOverview(material);
	}

	public void markReady(String content, OutlineResponse outline) {
		this.content = content;
		this.outline = outline;
		this.status = MaterialOverviewStatus.READY;
	}

	public void markFailed() {
		this.content = null;
		this.outline = null;
		this.status = MaterialOverviewStatus.FAILED;
	}

	public Long getMaterialId() {
		return material.getId();
	}

	public String getContent() {
		return content;
	}

	public OutlineResponse getOutline() {
		return outline;
	}

	public MaterialOverviewStatus getStatus() {
		return status;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
