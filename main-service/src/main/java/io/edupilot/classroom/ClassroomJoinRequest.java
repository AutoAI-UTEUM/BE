package io.edupilot.classroom;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
@Table(name = "classroom_join_requests")
public class ClassroomJoinRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "classroom_id", nullable = false)
	private Classroom classroom;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ClassroomJoinRequestStatus status;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ClassroomJoinRequest() {
	}

	private ClassroomJoinRequest(Classroom classroom, User user, Instant requestedAt) {
		this.classroom = classroom;
		this.user = user;
		this.status = ClassroomJoinRequestStatus.PENDING;
		this.requestedAt = requestedAt;
	}

	public static ClassroomJoinRequest create(
		Classroom classroom,
		User user,
		Instant requestedAt
	) {
		return new ClassroomJoinRequest(classroom, user, requestedAt);
	}

	public void requestAgain(Instant requestedAt) {
		this.status = ClassroomJoinRequestStatus.PENDING;
		this.requestedAt = requestedAt;
		this.processedAt = null;
	}

	public void approve(Instant processedAt) {
		this.status = ClassroomJoinRequestStatus.APPROVED;
		this.processedAt = processedAt;
	}

	public void reject(Instant processedAt) {
		this.status = ClassroomJoinRequestStatus.REJECTED;
		this.processedAt = processedAt;
	}

	public Long getId() {
		return id;
	}

	public Classroom getClassroom() {
		return classroom;
	}

	public Long getClassroomId() {
		return classroom.getId();
	}

	public String getClassroomName() {
		return classroom.getName();
	}

	public User getUser() {
		return user;
	}

	public ClassroomJoinRequestStatus getStatus() {
		return status;
	}

	public Instant getRequestedAt() {
		return requestedAt;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}
}
