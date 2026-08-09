package io.edupilot.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.diagnosis.DiagnosisService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.material.MaterialProcessingStatus;
import io.edupilot.session.dto.ConversationStartResponse;
import io.edupilot.session.dto.PageStateResponse;
import io.edupilot.session.dto.SessionCreateResponse;
import io.edupilot.session.dto.SessionDetailResponse;
import io.edupilot.session.dto.SessionListResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
public class SessionService {

	static final Duration TURN_CLAIM_TTL = Duration.ofMinutes(5);

	private final LearningSessionRepository sessionRepository;
	private final UserRepository userRepository;
	private final StateReducer stateReducer;
	private final Clock clock;
	private final DiagnosisService diagnosisService;
	private final MaterialAccessService materialAccessService;
	private final UiActionResolver uiActionResolver;

	public SessionService(
		LearningSessionRepository sessionRepository,
		UserRepository userRepository,
		StateReducer stateReducer,
		Clock clock,
		DiagnosisService diagnosisService,
		MaterialAccessService materialAccessService,
		UiActionResolver uiActionResolver
	) {
		this.sessionRepository = sessionRepository;
		this.userRepository = userRepository;
		this.stateReducer = stateReducer;
		this.clock = clock;
		this.diagnosisService = diagnosisService;
		this.materialAccessService = materialAccessService;
		this.uiActionResolver = uiActionResolver;
	}

	@Transactional
	public SessionCreateResponse create(Long userId, Long materialId) {
		LearningMaterial material = materialAccessService.requireAccessibleForUpdate(
			userId,
			materialId
		);
		validateReady(material);

		return sessionRepository.findByUser_IdAndMaterial_IdAndStatus(
				userId,
				materialId,
				SessionStatus.ACTIVE
			)
			.map(session -> SessionCreateResponse.from(session, true))
			.orElseGet(() -> {
				User user = userRepository.getReferenceById(userId);
				LearningSession session = sessionRepository.saveAndFlush(
					LearningSession.create(user, material)
				);
				return SessionCreateResponse.from(session, false);
			});
	}

	@Transactional(readOnly = true)
	public SessionListResponse list(
		Long userId,
		int page,
		int size,
		SessionStatus status
	) {
		List<SessionStatus> statuses;
		if (status == null) {
			statuses = List.of(SessionStatus.ACTIVE, SessionStatus.COMPLETED);
		} else if (status == SessionStatus.DELETED) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		} else {
			statuses = List.of(status);
		}
		PageRequest pageable = PageRequest.of(
			page,
			size,
			Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))
		);
		Page<LearningSession> sessions = sessionRepository.findByUser_IdAndStatusIn(
			userId,
			statuses,
			pageable
		);
		return SessionListResponse.from(sessions);
	}

	@Transactional(readOnly = true)
	public SessionDetailResponse detail(Long userId, Long sessionId) {
		LearningSession session = visibleOwnedSession(userId, sessionId);
		return SessionDetailResponse.from(
			session,
			diagnosisService.findPending(
					session.getId(),
					session.getPendingDiagnosisId()
				)
				.orElse(null)
		);
	}

	@Transactional
	public SessionDetailResponse complete(Long userId, Long sessionId) {
		LearningSession session = ownedSessionForUpdate(userId, sessionId);
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
		}
		materialAccessService.assertAccessible(userId, session.getMaterialId());
		assertNoLiveTurn(session);
		session.complete();
		sessionRepository.flush();
		return SessionDetailResponse.from(session);
	}

	@Transactional
	public List<UiAction> declineQuizProposal(Long userId, Long sessionId) {
		materialAccessService.assertSessionAccessible(userId, sessionId);
		LearningSession session = ownedSessionForUpdate(userId, sessionId);
		if (session.getStatus() == SessionStatus.DELETED) {
			throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
		}
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_NOT_ACTIVE);
		}

		List<UiAction> nextUiActions = uiActionResolver.nextLearning(
			session.getCurrentPage(),
			session.getMaterialPageCount()
		);
		if (session.declineQuizProposal(nextUiActions)) {
			sessionRepository.flush();
		}
		return session.getLastUiActions();
	}

	@Transactional
	public void delete(Long userId, Long sessionId) {
		LearningSession session = ownedSessionForUpdate(userId, sessionId);
		if (session.getStatus() == SessionStatus.DELETED) {
			throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
		}
		assertNoLiveTurn(session);
		session.delete();
	}

	@Transactional
	public PageStateResponse movePage(
		Long userId,
		Long sessionId,
		int pageNumber
	) {
		LearningSession session = ownedSessionForUpdate(userId, sessionId);
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_NOT_ACTIVE);
		}
		materialAccessService.assertAccessible(userId, session.getMaterialId());
		Integer pageCount = session.getMaterialPageCount();
		if (pageNumber < 1 || pageCount == null || pageNumber > pageCount) {
			throw new BusinessException(ErrorCode.PAGE_OUT_OF_RANGE);
		}
		assertNoLiveTurn(session);

		StateReducer.PageTransition transition = stateReducer.movePage(
			session.getCurrentPage(),
			pageNumber
		);
		if (transition.changed()) {
			session.moveTo(
				transition.pageNumber(),
				transition.pageStatus(),
				transition.uiActions()
			);
			sessionRepository.flush();
		}
		return PageStateResponse.from(session);
	}

	@Transactional
	public ConversationStartResponse startNewConversation(
		Long userId,
		Long sessionId
	) {
		LearningSession session = ownedSessionForUpdate(userId, sessionId);
		if (session.getStatus() == SessionStatus.DELETED) {
			throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
		}
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_NOT_ACTIVE);
		}
		Instant startedAt = clock.instant();
		assertNoLiveTurn(session, startedAt);
		int conversationNumber = session.startNewConversation(startedAt);
		sessionRepository.flush();
		return ConversationStartResponse.of(conversationNumber, startedAt);
	}

	private void validateReady(LearningMaterial material) {
		if (material.getProcessingStatus() == MaterialProcessingStatus.PROCESSING) {
			throw new BusinessException(ErrorCode.MATERIAL_PROCESSING);
		}
		if (material.getProcessingStatus() == MaterialProcessingStatus.FAILED) {
			throw new BusinessException(ErrorCode.MATERIAL_PROCESSING_FAILED);
		}
	}

	private LearningSession visibleOwnedSession(Long userId, Long sessionId) {
		return sessionRepository.findByIdAndUser_Id(sessionId, userId)
			.filter(session -> session.getStatus() != SessionStatus.DELETED)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
	}

	private LearningSession ownedSessionForUpdate(Long userId, Long sessionId) {
		return sessionRepository.findOwnedForUpdate(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
	}

	private void assertNoLiveTurn(LearningSession session) {
		assertNoLiveTurn(session, clock.instant());
	}

	private void assertNoLiveTurn(LearningSession session, Instant now) {
		Instant staleBefore = now.minus(TURN_CLAIM_TTL);
		if (session.hasLiveTurn(staleBefore)) {
			throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
		}
		session.clearStaleTurn(staleBefore);
	}
}
