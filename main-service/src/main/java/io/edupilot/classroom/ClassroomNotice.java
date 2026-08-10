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
@Table(name = "classroom_notices")
public class ClassroomNotice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "classroom_id", nullable = false)
	private Classroom classroom;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;

	@Column(name = "week_number")
	private Integer weekNumber;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Column(name = "publish_at")
	private Instant publishAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ClassroomNotice() {
	}

	private ClassroomNotice(
		Classroom classroom,
		String title,
		String content,
		Integer weekNumber,
		Instant publishAt,
		Instant publishedAt
	) {
		this.classroom = classroom;
		this.title = title;
		this.content = content;
		this.weekNumber = weekNumber;
		this.publishAt = publishAt;
		this.publishedAt = publishedAt;
	}

	public static ClassroomNotice create(
		Classroom classroom,
		String title,
		String content,
		Instant publishedAt
	) {
		return create(
			classroom,
			title,
			content,
			null,
			null,
			publishedAt
		);
	}

	public static ClassroomNotice create(
		Classroom classroom,
		String title,
		String content,
		Integer weekNumber,
		Instant publishAt,
		Instant publishedAt
	) {
		return new ClassroomNotice(
			classroom,
			title,
			content,
			weekNumber,
			publishAt,
			publishedAt
		);
	}

	public void update(String title, String content) {
		update(title, content, false, null, false, null);
	}

	public void update(
		String title,
		String content,
		boolean weekNumberPresent,
		Integer weekNumber,
		boolean publishAtPresent,
		Instant publishAt
	) {
		if (title != null) {
			this.title = title;
		}
		if (content != null) {
			this.content = content;
		}
		if (weekNumberPresent) {
			this.weekNumber = weekNumber;
		}
		if (publishAtPresent) {
			this.publishAt = publishAt;
		}
	}

	public Long getId() {
		return id;
	}

	public Classroom getClassroom() {
		return classroom;
	}

	public String getTitle() {
		return title;
	}

	public String getContent() {
		return content;
	}

	public Integer getWeekNumber() {
		return weekNumber;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public Instant getPublishAt() {
		return publishAt;
	}

	public boolean isPublished(Instant now) {
		return publishAt == null || !publishAt.isAfter(now);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
