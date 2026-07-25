package io.edupilot.session;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.edupilot.material.LearningMaterial;
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
import jakarta.persistence.Version;

@Entity
@Table(name = "learning_sessions")
public class LearningSession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "material_id", nullable = false)
	private LearningMaterial material;

	@Column(name = "current_page", nullable = false)
	private int currentPage;

	@Enumerated(EnumType.STRING)
	@Column(name = "page_status", nullable = false, length = 30)
	private PageStatus pageStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SessionStatus status;

	@Column(name = "conversation_summary", columnDefinition = "TEXT")
	private String conversationSummary;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "last_ui_actions_json", columnDefinition = "json")
	private List<UiAction> lastUiActions;

	@Column(name = "active_quiz_id")
	private Long activeQuizId;

	@Column(name = "active_turn_request_id", length = 255)
	private String activeTurnRequestId;

	@Column(name = "active_turn_started_at")
	private Instant activeTurnStartedAt;

	@Version
	@Column(nullable = false)
	private long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected LearningSession() {
	}

	private LearningSession(User user, LearningMaterial material) {
		this.user = user;
		this.material = material;
		this.currentPage = 1;
		this.pageStatus = PageStatus.NOT_EXPLAINED;
		this.status = SessionStatus.ACTIVE;
		this.lastUiActions = List.of(UiAction.initialExplanation());
	}

	public static LearningSession create(User user, LearningMaterial material) {
		return new LearningSession(user, material);
	}

	public void moveTo(int pageNumber, PageStatus nextStatus, List<UiAction> uiActions) {
		this.currentPage = pageNumber;
		this.pageStatus = nextStatus;
		this.lastUiActions = List.copyOf(uiActions);
	}

	public void complete() {
		this.status = SessionStatus.COMPLETED;
	}

	public void completeQuizSubmission(Long quizId, List<UiAction> uiActions) {
		if (Objects.equals(this.activeQuizId, quizId)) {
			this.activeQuizId = null;
		}
		this.lastUiActions = List.copyOf(uiActions);
	}

	public void delete() {
		this.status = SessionStatus.DELETED;
		this.activeTurnRequestId = null;
		this.activeTurnStartedAt = null;
	}

	public boolean hasLiveTurn(Instant staleBefore) {
		return activeTurnRequestId != null
			&& (activeTurnStartedAt == null || !activeTurnStartedAt.isBefore(staleBefore));
	}

	public void clearStaleTurn(Instant staleBefore) {
		if (activeTurnRequestId != null
			&& activeTurnStartedAt != null
			&& activeTurnStartedAt.isBefore(staleBefore)) {
			activeTurnRequestId = null;
			activeTurnStartedAt = null;
		}
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return user.getId();
	}

	public Long getMaterialId() {
		return material.getId();
	}

	public String getMaterialTitle() {
		return material.getTitle();
	}

	public Integer getMaterialPageCount() {
		return material.getPageCount();
	}

	public int getCurrentPage() {
		return currentPage;
	}

	public PageStatus getPageStatus() {
		return pageStatus;
	}

	public SessionStatus getStatus() {
		return status;
	}

	public List<UiAction> getLastUiActions() {
		return lastUiActions == null ? List.of() : List.copyOf(lastUiActions);
	}

	public Long getActiveQuizId() {
		return activeQuizId;
	}

	public String getActiveTurnRequestId() {
		return activeTurnRequestId;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
