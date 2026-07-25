package io.edupilot.session;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Service
public class TurnClaimService {

	private final LearningSessionRepository sessionRepository;
	private final ChatMessageRepository messageRepository;
	private final Clock clock;

	public TurnClaimService(
		LearningSessionRepository sessionRepository,
		ChatMessageRepository messageRepository,
		Clock clock
	) {
		this.sessionRepository = sessionRepository;
		this.messageRepository = messageRepository;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void claim(Long userId, Long sessionId, String requestId) {
		LearningSession session = sessionRepository.findByIdAndUser_Id(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_NOT_ACTIVE);
		}
		if (messageRepository.existsBySession_IdAndRequestId(sessionId, requestId)) {
			throw new BusinessException(ErrorCode.TURN_ALREADY_PROCESSED);
		}

		Instant now = clock.instant();
		int claimed = sessionRepository.claimTurn(
			sessionId,
			userId,
			requestId,
			now,
			now.minus(SessionService.TURN_CLAIM_TTL)
		);
		if (claimed == 1) {
			return;
		}

		LearningSession current = sessionRepository.findByIdAndUser_Id(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		if (current.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_NOT_ACTIVE);
		}
		if (messageRepository.existsBySession_IdAndRequestId(sessionId, requestId)) {
			throw new BusinessException(ErrorCode.TURN_ALREADY_PROCESSED);
		}
		throw new BusinessException(ErrorCode.TURN_IN_PROGRESS);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void release(Long sessionId, String requestId) {
		sessionRepository.releaseTurn(sessionId, requestId);
	}
}
