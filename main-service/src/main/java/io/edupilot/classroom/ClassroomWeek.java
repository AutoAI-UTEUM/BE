package io.edupilot.classroom;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
@Table(name = "classroom_weeks")
public class ClassroomWeek {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "classroom_id", nullable = false)
	private Classroom classroom;

	@Column(name = "week_number", nullable = false)
	private int weekNumber;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(name = "release_at")
	private Instant releaseAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ClassroomWeek() {
	}

	private ClassroomWeek(
		Classroom classroom,
		int weekNumber,
		String title,
		Instant releaseAt
	) {
		this.classroom = classroom;
		this.weekNumber = weekNumber;
		this.title = title;
		this.releaseAt = releaseAt;
	}

	public static ClassroomWeek create(
		Classroom classroom,
		int weekNumber,
		String title,
		Instant releaseAt
	) {
		return new ClassroomWeek(classroom, weekNumber, title, releaseAt);
	}

	public void update(
		boolean titlePresent,
		String title,
		boolean releaseAtPresent,
		Instant releaseAt
	) {
		if (titlePresent) {
			this.title = title;
		}
		if (releaseAtPresent) {
			this.releaseAt = releaseAt;
		}
	}

	public boolean isReleased(Instant now) {
		return releaseAt == null || !releaseAt.isAfter(now);
	}

	public ClassroomWeekStatus statusAt(Instant now) {
		return isReleased(now)
			? ClassroomWeekStatus.PUBLISHED
			: ClassroomWeekStatus.SCHEDULED;
	}

	public Long getId() {
		return id;
	}

	public Classroom getClassroom() {
		return classroom;
	}

	public Long getClassroomId() {
		return classroom.getId();
	}

	public int getWeekNumber() {
		return weekNumber;
	}

	public String getTitle() {
		return title;
	}

	public Instant getReleaseAt() {
		return releaseAt;
	}
}
