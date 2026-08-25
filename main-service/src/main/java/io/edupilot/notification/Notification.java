package io.edupilot.notification;

import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.edupilot.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
	name = "notifications",
	indexes = @Index(
		name = "idx_notifications_user_created",
		columnList = "user_id, created_at"
	)
)
@Check(constraints = "type IN ('MATERIAL_UPLOADED', 'NOTICE_PUBLISHED', "
	+ "'JOIN_REQUEST_RECEIVED', 'JOIN_REQUEST_PROCESSED')")
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private NotificationType type;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String body;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "link_json", nullable = false, columnDefinition = "json")
	private Map<String, Object> link;

	@Column(name = "read_at")
	private Instant readAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Notification() {
	}

	private Notification(
		User user,
		NotificationType type,
		String title,
		String body,
		Map<String, Object> link,
		Instant createdAt
	) {
		this.user = user;
		this.type = type;
		this.title = title;
		this.body = body;
		this.link = Map.copyOf(link);
		this.createdAt = createdAt;
	}

	public static Notification create(
		User user,
		NotificationType type,
		String title,
		String body,
		Map<String, Object> link,
		Instant createdAt
	) {
		return new Notification(user, type, title, body, link, createdAt);
	}

	public void markRead(Instant now) {
		if (readAt == null) {
			readAt = now;
		}
	}

	public Long getId() {
		return id;
	}

	public NotificationType getType() {
		return type;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public Map<String, Object> getLink() {
		return link;
	}

	public Instant getReadAt() {
		return readAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
