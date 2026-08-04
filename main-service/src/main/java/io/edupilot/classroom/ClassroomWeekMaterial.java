package io.edupilot.classroom;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.edupilot.material.LearningMaterial;
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
@Table(name = "classroom_week_materials")
public class ClassroomWeekMaterial {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "week_id", nullable = false)
	private ClassroomWeek week;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "material_id", nullable = false)
	private LearningMaterial material;

	@Column(name = "added_at", nullable = false)
	private Instant addedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ClassroomWeekMaterial() {
	}

	private ClassroomWeekMaterial(
		ClassroomWeek week,
		LearningMaterial material,
		Instant addedAt
	) {
		this.week = week;
		this.material = material;
		this.addedAt = addedAt;
	}

	public static ClassroomWeekMaterial create(
		ClassroomWeek week,
		LearningMaterial material,
		Instant addedAt
	) {
		return new ClassroomWeekMaterial(week, material, addedAt);
	}

	public Long getId() {
		return id;
	}

	public LearningMaterial getMaterial() {
		return material;
	}

	public ClassroomWeek getWeek() {
		return week;
	public Long getWeekId() {
		return week.getId();
	}

	public Instant getAddedAt() {
		return addedAt;
	}
}
