package io.edupilot.note;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.edupilot.material.LearningMaterial;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.LearningSession;
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
@Table(name = "notes")
public class Note {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "material_id", nullable = false)
	private LearningMaterial material;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "session_id")
	private LearningSession session;

	@Column(name = "page_number")
	private Integer pageNumber;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "source_message_id")
	private ChatMessage sourceMessage;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Note() {
	}

	private Note(
		User user,
		LearningMaterial material,
		LearningSession session,
		Integer pageNumber,
		ChatMessage sourceMessage,
		String content
	) {
		this.user = user;
		this.material = material;
		this.session = session;
		this.pageNumber = pageNumber;
		this.sourceMessage = sourceMessage;
		this.content = content;
	}

	public static Note create(
		User user,
		LearningMaterial material,
		LearningSession session,
		Integer pageNumber,
		ChatMessage sourceMessage,
		String content
	) {
		return new Note(
			user,
			material,
			session,
			pageNumber,
			sourceMessage,
			content
		);
	}

	public void updateContent(String content) {
		this.content = content;
	}

	public Long getId() {
		return id;
	}

	public Long getSessionId() {
		return session == null ? null : session.getId();
	}

	public Long getMaterialId() {
		return material.getId();
	}

	public String getContent() {
		return content;
	}

	public Integer getPageNumber() {
		return pageNumber;
	}

	public Long getSourceMessageId() {
		return sourceMessage == null ? null : sourceMessage.getId();
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
