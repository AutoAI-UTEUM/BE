package io.edupilot.classroom;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
@Table(name = "classroom_resource")
public class ClassroomResource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "classroom_id", nullable = false)
	private Classroom classroom;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ClassroomResourceType type;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(name = "week_number")
	private Integer weekNumber;

	@Column(name = "file_name", length = 255)
	private String fileName;

	@Column(name = "content_type", length = 255)
	private String contentType;

	@Column(name = "size_bytes")
	private Long sizeBytes;

	@Column(name = "storage_path", length = 255)
	private String storagePath;

	@Column(length = 2048)
	private String url;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ClassroomResource() {
	}

	private ClassroomResource(
		Classroom classroom,
		ClassroomResourceType type,
		String title,
		Integer weekNumber,
		String fileName,
		String contentType,
		Long sizeBytes,
		String storagePath,
		String url
	) {
		this.classroom = classroom;
		this.type = type;
		this.title = title;
		this.weekNumber = weekNumber;
		this.fileName = fileName;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.storagePath = storagePath;
		this.url = url;
	}

	public static ClassroomResource file(
		Classroom classroom,
		String title,
		Integer weekNumber,
		String fileName,
		String contentType,
		long sizeBytes,
		String storagePath
	) {
		return new ClassroomResource(
			classroom,
			ClassroomResourceType.FILE,
			title,
			weekNumber,
			fileName,
			contentType,
			sizeBytes,
			storagePath,
			null
		);
	}

	public static ClassroomResource link(
		Classroom classroom,
		String title,
		Integer weekNumber,
		String url
	) {
		return new ClassroomResource(
			classroom,
			ClassroomResourceType.LINK,
			title,
			weekNumber,
			null,
			null,
			null,
			null,
			url
		);
	}

	public void update(String title, boolean weekNumberPresent, Integer weekNumber) {
		if (title != null) {
			this.title = title;
		}
		if (weekNumberPresent) {
			this.weekNumber = weekNumber;
		}
	}

	public Long getId() {
		return id;
	}

	public Classroom getClassroom() {
		return classroom;
	}

	public ClassroomResourceType getType() {
		return type;
	}

	public String getTitle() {
		return title;
	}

	public Integer getWeekNumber() {
		return weekNumber;
	}

	public String getFileName() {
		return fileName;
	}

	public String getContentType() {
		return contentType;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public String getStoragePath() {
		return storagePath;
	}

	public String getUrl() {
		return url;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
