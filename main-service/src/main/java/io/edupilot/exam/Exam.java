package io.edupilot.exam;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomStatus;
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
@Table(name = "exams")
public class Exam {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "classroom_id", nullable = false)
	private Classroom classroom;

	@Column(name = "week_number")
	private Integer weekNumber;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(length = 500)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ExamStatus status;

	@Column(name = "allow_retake", nullable = false)
	private boolean allowRetake;

	@Column(name = "total_score", nullable = false, precision = 10, scale = 2)
	private BigDecimal totalScore;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Exam() {
	}

	private Exam(
		Classroom classroom,
		Integer weekNumber,
		String title,
		String description,
		boolean allowRetake
	) {
		this.classroom = classroom;
		this.weekNumber = weekNumber;
		this.title = title;
		this.description = description;
		this.status = ExamStatus.DRAFT;
		this.allowRetake = allowRetake;
		this.totalScore = BigDecimal.ZERO;
	}

	public static Exam create(
		Classroom classroom,
		Integer weekNumber,
		String title,
		String description,
		boolean allowRetake
	) {
		return new Exam(classroom, weekNumber, title, description, allowRetake);
	}

	public void update(
		String title,
		boolean descriptionPresent,
		String description,
		boolean weekNumberPresent,
		Integer weekNumber,
		Boolean allowRetake
	) {
		if (title != null) {
			this.title = title;
		}
		if (descriptionPresent) {
			this.description = description;
		}
		if (weekNumberPresent) {
			this.weekNumber = weekNumber;
		}
		if (allowRetake != null) {
			this.allowRetake = allowRetake;
		}
	}

	public void replaceTotalScore(BigDecimal totalScore) {
		this.totalScore = totalScore;
	}

	public void publish(Instant publishedAt) {
		this.status = ExamStatus.PUBLISHED;
		this.publishedAt = publishedAt;
	}

	public void close(Instant closedAt) {
		this.status = ExamStatus.CLOSED;
		this.closedAt = closedAt;
	}

	public Long getId() {
		return id;
	}

	public Long getClassroomId() {
		return classroom.getId();
	}

	public Long getInstructorId() {
		return classroom.getInstructorId();
	}

	public ClassroomStatus getClassroomStatus() {
		return classroom.getStatus();
	}

	public int getClassroomWeekCount() {
		return classroom.getWeekCount();
	}

	public Integer getWeekNumber() {
		return weekNumber;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public ExamStatus getStatus() {
		return status;
	}

	public boolean isAllowRetake() {
		return allowRetake;
	}

	public BigDecimal getTotalScore() {
		return totalScore;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public Instant getClosedAt() {
		return closedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
