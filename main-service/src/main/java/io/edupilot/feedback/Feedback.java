package io.edupilot.feedback;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

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
@Table(name = "feedbacks")
public class Feedback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private FeedbackCategory category;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String message;

	@Column(name = "page_url", columnDefinition = "TEXT")
	private String pageUrl;

	@Column(name = "client_version", columnDefinition = "TEXT")
	private String clientVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Feedback() {
	}

	private Feedback(
		User user,
		FeedbackCategory category,
		String message,
		String pageUrl,
		String clientVersion
	) {
		this.user = user;
		this.category = category;
		this.message = message;
		this.pageUrl = pageUrl;
		this.clientVersion = clientVersion;
	}

	public static Feedback create(
		User user,
		FeedbackCategory category,
		String message,
		String pageUrl,
		String clientVersion
	) {
		return new Feedback(user, category, message, pageUrl, clientVersion);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return user.getId();
	}

	public FeedbackCategory getCategory() {
		return category;
	}

	public String getMessage() {
		return message;
	}

	public String getPageUrl() {
		return pageUrl;
	}

	public String getClientVersion() {
		return clientVersion;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
