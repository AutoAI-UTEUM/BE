package io.edupilot.session;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "qa_messages")
public class QaMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "qa_thread_id", nullable = false)
	private QaThread thread;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "chat_message_id", nullable = false)
	private ChatMessage chatMessage;

	@Enumerated(EnumType.STRING)
	@Column(name = "sender_type", nullable = false, length = 20)
	private SenderType senderType;

	@Column(nullable = false, columnDefinition = "MEDIUMTEXT")
	private String content;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected QaMessage() {
	}

	private QaMessage(QaThread thread, ChatMessage chatMessage) {
		this.thread = thread;
		this.chatMessage = chatMessage;
		this.senderType = chatMessage.getSenderType();
		this.content = chatMessage.getContent();
	}

	public static QaMessage from(QaThread thread, ChatMessage chatMessage) {
		return new QaMessage(thread, chatMessage);
	}

	public SenderType getSenderType() {
		return senderType;
	}

	public Long getId() {
		return id;
	}

	public String getContent() {
		return content;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
