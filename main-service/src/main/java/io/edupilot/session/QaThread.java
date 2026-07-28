package io.edupilot.session;

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
@Table(name = "qa_threads")
public class QaThread {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private LearningSession session;

	@Column(name = "page_number", nullable = false)
	private int pageNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private QaThreadStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected QaThread() {
	}

	private QaThread(LearningSession session) {
		this.session = session;
		this.pageNumber = session.getCurrentPage();
		this.status = QaThreadStatus.ACTIVE;
	}

	public static QaThread start(LearningSession session) {
		return new QaThread(session);
	}

	public void close() {
		this.status = QaThreadStatus.CLOSED;
	}

	public Long getId() {
		return id;
	}

	public Long getSessionId() {
		return session.getId();
	}

	public QaThreadStatus getStatus() {
		return status;
	}
}
