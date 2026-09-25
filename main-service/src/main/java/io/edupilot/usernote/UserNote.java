package io.edupilot.usernote;

import java.time.Instant;

import io.edupilot.material.LearningMaterial;
import io.edupilot.user.User;
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
@Table(name = "user_notes")
public class UserNote {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "material_id")
	private LearningMaterial material;

	@Column(name = "page_number")
	private Integer pageNumber;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "MEDIUMTEXT")
	private String content;

	@Column(name = "client_id", length = 64)
	private String clientId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected UserNote() {
	}

	private UserNote(
		User user, LearningMaterial material, Integer pageNumber,
		String title, String content, String clientId, Instant createdAt, Instant now
	) {
		this.user = user;
		this.material = material;
		this.pageNumber = pageNumber;
		this.title = title;
		this.content = content;
		this.clientId = clientId;
		this.createdAt = createdAt;
		this.updatedAt = now;
	}

	public static UserNote create(
		User user, LearningMaterial material, Integer pageNumber,
		String title, String content, String clientId, Instant createdAt, Instant now
	) {
		return new UserNote(user, material, pageNumber, title, content, clientId, createdAt, now);
	}

	public void update(
		String title, boolean titlePresent,
		String content, boolean contentPresent,
		Integer pageNumber, boolean pageNumberPresent,
		Instant now
	) {
		if (titlePresent) {
			this.title = title;
		}
		if (contentPresent) {
			this.content = content;
		}
		if (pageNumberPresent) {
			this.pageNumber = pageNumber;
		}
		this.updatedAt = now;
	}

	public void delete(Instant now) {
		this.deletedAt = now;
		this.updatedAt = now;
	}

	public Long getId() { return id; }
	public Long getMaterialId() { return material == null ? null : material.getId(); }
	public Integer getPageNumber() { return pageNumber; }
	public String getTitle() { return title; }
	public String getContent() { return content; }
	public String getClientId() { return clientId; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public Instant getDeletedAt() { return deletedAt; }
}
