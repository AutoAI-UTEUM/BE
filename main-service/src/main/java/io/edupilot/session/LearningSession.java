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

	@Column(name = "last_summarized_message_id")
	private Long lastSummarizedMessageId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "last_ui_actions_json", columnDefinition = "json")
	private List<UiAction> lastUiActions;

	@Column(name = "active_quiz_id")
	private Long activeQuizId;

	@Column(name = "pending_diagnosis_id")
	private Long pendingDiagnosisId;

	@Column(name = "active_turn_request_id", length = 255)
	private String activeTurnRequestId;

	@Column(name = "active_turn_started_at")
	private Instant activeTurnStartedAt;

	@Column(name = "conversation_reset_at")
	private Instant conversationResetAt;

	@Column(name = "conversation_reset_count", nullable = false)
	private int conversationResetCount;

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

	public void completeQuizSubmission(
		Long quizId,
		List<UiAction> uiActions,
		boolean passed,
		boolean currentPageQuiz
	) {
		if (Objects.equals(this.activeQuizId, quizId)) {
			this.activeQuizId = null;
		}
		if (!currentPageQuiz) {
			return;
		}
		if (passed) {
			this.pageStatus = PageStatus.EXPLAINED;
		}
		this.lastUiActions = List.copyOf(uiActions);
	}

	public boolean declineQuizProposal(List<UiAction> nextUiActions) {
		boolean hasQuizProposal = getLastUiActions().stream()
			.anyMatch(action -> "SHOW_QUIZ_TYPE_SELECT".equals(action.yesEvent()));
		if (!hasQuizProposal) {
			return false;
		}
		this.lastUiActions = List.copyOf(nextUiActions);
		return true;
	}

	public void startDiagnosis(Long diagnosisId, UiAction uiAction) {
		this.pendingDiagnosisId = diagnosisId;
		this.pageStatus = PageStatus.DIAGNOSIS_PENDING;
		this.lastUiActions = List.of(uiAction);
	}

	public void completeDiagnosis(
		Long diagnosisId,
		boolean currentPageDiagnosis
	) {
		if (Objects.equals(this.pendingDiagnosisId, diagnosisId)) {
			this.pendingDiagnosisId = null;
			if (currentPageDiagnosis) {
				this.pageStatus = PageStatus.REPAIR_COMPLETED;
			}
		}
	}

	public void applyAiTurn(
		PageStatus nextPageStatus,
		List<UiAction> uiActions,
		boolean pageStatusChanged
	) {
		if (nextPageStatus != null) {
			this.pageStatus = nextPageStatus;
		}
		if (pageStatusChanged) {
			this.lastUiActions = List.copyOf(uiActions);
		}
	}

	public void activateQuiz(Long quizId, List<UiAction> uiActions) {
		this.activeQuizId = Objects.requireNonNull(quizId);
		this.pageStatus = PageStatus.QUIZ_READY;
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

	public int startNewConversation(Instant startedAt) {
		this.conversationResetAt = Objects.requireNonNull(startedAt);
		this.conversationResetCount += 1;
		this.conversationSummary = null;
		this.lastSummarizedMessageId = null;
		return this.conversationResetCount;
	}

	public void applyConversationSummary(
		String summary,
		Long summarizedThroughMessageId
	) {
		this.conversationSummary = Objects.requireNonNull(summary);
		this.lastSummarizedMessageId = Objects.requireNonNull(
			summarizedThroughMessageId
		);
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

	public String getMaterialXaiFileId() {
		return material.getXaiFileId();
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

	public Long getPendingDiagnosisId() {
		return pendingDiagnosisId;
	}

	public String getActiveTurnRequestId() {
		return activeTurnRequestId;
	}

	public Instant getConversationResetAt() {
		return conversationResetAt;
	}

	public String getConversationSummary() {
		return conversationSummary;
	}

	public Long getLastSummarizedMessageId() {
		return lastSummarizedMessageId;
	}

	public int getConversationResetCount() {
		return conversationResetCount;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
