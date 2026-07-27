package io.edupilot.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.edupilot.diagnosis.Diagnosis;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.diagnosis.DiagnosisStatus;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Service
public class TurnPreparationService {

	private final LearningSessionRepository sessionRepository;
	private final ChatMessageRepository messageRepository;
	private final DiagnosisRepository diagnosisRepository;

	public TurnPreparationService(
		LearningSessionRepository sessionRepository,
		ChatMessageRepository messageRepository,
		DiagnosisRepository diagnosisRepository
	) {
		this.sessionRepository = sessionRepository;
		this.messageRepository = messageRepository;
		this.diagnosisRepository = diagnosisRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PreparedTurn prepare(
		Long userId,
		Long sessionId,
		String requestId,
		String userContent,
		Long diagnosisId
	) {
		LearningSession session = sessionRepository.findOwnedForUpdate(
				sessionId,
				userId
			)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_NOT_ACTIVE);
		}
		if (!requestId.equals(session.getActiveTurnRequestId())) {
			throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
		}

		if (diagnosisId != null) {
			answerDiagnosis(session, userId, diagnosisId, userContent);
		}

		ChatMessage message = messageRepository.saveAndFlush(
			ChatMessage.user(session, userContent, requestId)
		);
		return new PreparedTurn(message.getId());
	}

	private void answerDiagnosis(
		LearningSession session,
		Long userId,
		Long diagnosisId,
		String answer
	) {
		Diagnosis diagnosis = diagnosisRepository.findByIdForUpdate(diagnosisId)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND));
		if (!diagnosis.getSessionId().equals(session.getId())
			|| !diagnosis.getUserId().equals(userId)) {
			throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND);
		}
		if (!diagnosisId.equals(session.getPendingDiagnosisId())
			|| !StringUtils.hasText(answer)) {
			throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_PENDING);
		}
		String normalized = answer.trim();
		if (diagnosis.getStatus() == DiagnosisStatus.ANSWERED
			&& normalized.equals(diagnosis.getUserAnswer())) {
			return;
		}
		if (diagnosis.getStatus() != DiagnosisStatus.PENDING) {
			throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_PENDING);
		}
		diagnosis.answer(normalized);
		diagnosisRepository.flush();
	}
}
