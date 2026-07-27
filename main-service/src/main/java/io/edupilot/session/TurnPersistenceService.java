package io.edupilot.session;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.diagnosis.DiagnosisService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.dto.MessageResponse;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.session.dto.TurnStateResponse;

@Service
public class TurnPersistenceService {

	private static final String STUB_RESPONSE = "AI 연동 전 임시 응답";

	private final LearningSessionRepository sessionRepository;
	private final ChatMessageRepository messageRepository;
	private final DiagnosisService diagnosisService;

	public TurnPersistenceService(
		LearningSessionRepository sessionRepository,
		ChatMessageRepository messageRepository,
		DiagnosisService diagnosisService
	) {
		this.sessionRepository = sessionRepository;
		this.messageRepository = messageRepository;
		this.diagnosisService = diagnosisService;
	}

	@Transactional
	public TurnResponse persist(
		Long userId,
		Long sessionId,
		String requestId,
		String userContent,
		Long diagnosisId
	) {
		LearningSession session = sessionRepository.findByIdAndUser_Id(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_NOT_ACTIVE);
		}
		if (!requestId.equals(session.getActiveTurnRequestId())) {
			throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
		}
		if (diagnosisId != null) {
			diagnosisService.answer(
				userId,
				sessionId,
				diagnosisId,
				userContent
			);
		}

		messageRepository.saveAndFlush(
			ChatMessage.user(session, userContent, requestId)
		);
		ChatMessage aiMessage = messageRepository.saveAndFlush(
			ChatMessage.ai(session, STUB_RESPONSE)
		);
		return new TurnResponse(
			"stub:" + requestId,
			session.getId(),
			List.of(MessageResponse.from(aiMessage)),
			session.getLastUiActions(),
			new TurnStateResponse(
				session.getCurrentPage(),
				session.getPageStatus()
			)
		);
	}
}
