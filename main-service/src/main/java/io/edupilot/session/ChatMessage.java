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
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private LearningSession session;

	@Enumerated(EnumType.STRING)
	@Column(name = "sender_type", nullable = false, length = 20)
	private SenderType senderType;

	@Enumerated(EnumType.STRING)
	@Column(name = "message_type", nullable = false, length = 30)
	private MessageType messageType;

	@Column(nullable = false, columnDefinition = "MEDIUMTEXT")
	private String content;

	@Column(name = "page_number", nullable = false)
	private int pageNumber;

	@Column(name = "request_id", length = 255)
	private String requestId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ChatMessageStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ChatMessage() {
	}

	private ChatMessage(
		LearningSession session,
		SenderType senderType,
		MessageType messageType,
		String content,
		String requestId
	) {
		this.session = session;
		this.senderType = senderType;
		this.messageType = messageType;
		this.content = content;
		this.pageNumber = session.getCurrentPage();
		this.requestId = requestId;
		this.status = ChatMessageStatus.COMPLETED;
	}

	public static ChatMessage user(
		LearningSession session,
		String content,
		String requestId
	) {
		return new ChatMessage(
			session,
			SenderType.USER,
			MessageType.TEXT,
			content,
			requestId
		);
	}

	public static ChatMessage ai(LearningSession session, String content) {
		return ai(session, MessageType.TEXT, content);
	}

	public static ChatMessage ai(
		LearningSession session,
		MessageType messageType,
		String content
	) {
		return new ChatMessage(
			session,
			SenderType.AI,
			messageType,
			content,
			null
		);
	}

	public Long getId() {
		return id;
	}

	public SenderType getSenderType() {
		return senderType;
	}

	public MessageType getMessageType() {
		return messageType;
	}

	public String getContent() {
		return content;
	}

	public int getPageNumber() {
		return pageNumber;
	}

	public String getRequestId() {
		return requestId;
	}

	public ChatMessageStatus getStatus() {
		return status;
	}

	public void markFailed() {
		this.status = ChatMessageStatus.FAILED;
	}

	public void retry() {
		if (status == ChatMessageStatus.FAILED) {
			this.status = ChatMessageStatus.COMPLETED;
		}
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
