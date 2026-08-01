package io.edupilot.classroom;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
@Table(name = "classrooms")
public class Classroom {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instructor_id", nullable = false)
	private User instructor;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ClassroomColor color;

	@Column(length = 255)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ClassroomStatus status;

	@Column(name = "invite_code", nullable = false, unique = true, length = 16)
	private String inviteCode;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Classroom() {
	}

	private Classroom(
		User instructor,
		String name,
		LocalDate startDate,
		LocalDate endDate,
		ClassroomColor color,
		String description,
		String inviteCode
	) {
		this.instructor = instructor;
		this.name = name;
		this.startDate = startDate;
		this.endDate = endDate;
		this.color = color;
		this.description = description;
		this.status = ClassroomStatus.ACTIVE;
		this.inviteCode = inviteCode;
	}

	public static Classroom create(
		User instructor,
		String name,
		LocalDate startDate,
		LocalDate endDate,
		ClassroomColor color,
		String description,
		String inviteCode
	) {
		return new Classroom(
			instructor,
			name,
			startDate,
			endDate,
			color,
			description,
			inviteCode
		);
	}

	public void update(
		String name,
		LocalDate endDate,
		ClassroomColor color,
		boolean descriptionPresent,
		String description
	) {
		if (name != null) {
			this.name = name;
		}
		if (endDate != null) {
			this.endDate = endDate;
		}
		if (color != null) {
			this.color = color;
		}
		if (descriptionPresent) {
			this.description = description;
		}
	}

	public void complete() {
		this.status = ClassroomStatus.COMPLETED;
	}

	public void regenerateInviteCode(String inviteCode) {
		this.inviteCode = inviteCode;
	}

	public int getWeekCount() {
		long inclusiveDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
		return Math.toIntExact((inclusiveDays + 6) / 7);
	}

	public Long getId() {
		return id;
	}

	public Long getInstructorId() {
		return instructor.getId();
	}

	public String getInstructorName() {
		return instructor.getName();
	}

	public String getName() {
		return name;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public ClassroomColor getColor() {
		return color;
	}

	public String getDescription() {
		return description;
	}

	public ClassroomStatus getStatus() {
		return status;
	}

	public String getInviteCode() {
		return inviteCode;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
