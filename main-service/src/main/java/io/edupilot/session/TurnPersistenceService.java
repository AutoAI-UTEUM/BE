package io.edupilot.session;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.edupilot.diagnosis.DiagnosisService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.memory.LearnerMemoryCandidate;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.memory.MemoryEvidenceRef;
import io.edupilot.memory.MemoryWrite;
import io.edupilot.quiz.QuizProperties;
import io.edupilot.quiz.QuizService;
import io.edupilot.session.dto.MessageResponse;
import io.edupilot.session.dto.TurnStateResponse;
import io.edupilot.user.UserRepository;

@Service
public class TurnPersistenceService {

	private final LearningSessionRepository sessionRepository;
	private final SessionPageRecordRepository pageRecordRepository;
	private final ChatMessageRepository messageRepository;
	private final QaThreadRepository qaThreadRepository;
	private final QaMessageRepository qaMessageRepository;
	private final LearnerMemoryCandidateRepository candidateRepository;
	private final UserRepository userRepository;
	private final LearningMaterialRepository materialRepository;
	private final MaterialPageRepository materialPageRepository;
	private final QuizService quizService;
	private final QuizProperties quizProperties;
	private final DiagnosisService diagnosisService;
	private final UiActionResolver uiActionResolver;
	private final Clock clock;

	public TurnPersistenceService(
		LearningSessionRepository sessionRepository,
		SessionPageRecordRepository pageRecordRepository,
		ChatMessageRepository messageRepository,
		QaThreadRepository qaThreadRepository,
		QaMessageRepository qaMessageRepository,
		LearnerMemoryCandidateRepository candidateRepository,
		UserRepository userRepository,
		LearningMaterialRepository materialRepository,
		MaterialPageRepository materialPageRepository,
		QuizService quizService,
		QuizProperties quizProperties,
		DiagnosisService diagnosisService,
		UiActionResolver uiActionResolver,
		Clock clock
	) {
		this.sessionRepository = sessionRepository;
		this.pageRecordRepository = pageRecordRepository;
		this.messageRepository = messageRepository;
		this.qaThreadRepository = qaThreadRepository;
		this.qaMessageRepository = qaMessageRepository;
		this.candidateRepository = candidateRepository;
		this.userRepository = userRepository;
		this.materialRepository = materialRepository;
		this.materialPageRepository = materialPageRepository;
		this.quizService = quizService;
		this.quizProperties = quizProperties;
		this.diagnosisService = diagnosisService;
		this.uiActionResolver = uiActionResolver;
		this.clock = clock;
	}

	@Transactional
	public PersistedTurn persist(
		Long userId,
		Long sessionId,
		String requestId,
		TurnEventType eventType,
		Long diagnosisId,
		Long userMessageId,
		io.edupilot.ai.dto.TurnResponse aiResponse
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

		PageStatus previousPageStatus = session.getPageStatus();
		List<ChatMessage> aiMessages = saveAiMessages(
			session,
			aiResponse.messages()
		);
		applyQaThread(
			session,
			eventType,
			userMessageId,
			aiMessages,
			aiResponse.statePatch()
		);

		PageStatus nextPageStatus = pageStatus(aiResponse.statePatch());
		validatePendingDiagnosis(
			session,
			eventType,
			aiResponse.statePatch()
		);

		Long activeQuizId = null;
		List<UiAction> uiActions;
		if (eventType == TurnEventType.QUIZ_TYPE_SELECTED) {
			if (nextPageStatus != null
				&& nextPageStatus != PageStatus.QUIZ_READY) {
				throw policy();
			}
			activeQuizId = quizService.createFromGeneration(
				sessionId,
				aiResponse.schemaVersion(),
				aiResponse.quiz()
			);
			uiActions = uiActionResolver.forPageTransition(
				previousPageStatus,
				PageStatus.QUIZ_READY,
				session.getCurrentPage(),
				session.getMaterialPageCount(),
				false
			);
			session.activateQuiz(activeQuizId, uiActions);
		} else {
			if (eventType == TurnEventType.DIAGNOSIS_ANSWER_SUBMITTED) {
				completeDiagnosis(
					diagnosisId,
					aiMessages,
					nextPageStatus,
					aiResponse.statePatch()
				);
			}
			PageStatus finalPageStatus = nextPageStatus == null
				? session.getPageStatus()
				: nextPageStatus;
			boolean pageStatusChanged =
				finalPageStatus != previousPageStatus;
			boolean quizEligible = isQuizEligible(
				session,
				eventType,
				finalPageStatus,
				pageStatusChanged
			);
			uiActions = uiActionResolver.forPageTransition(
				previousPageStatus,
				finalPageStatus,
				session.getCurrentPage(),
				session.getMaterialPageCount(),
				quizEligible
			);
			session.applyAiTurn(
				nextPageStatus,
				uiActions,
				pageStatusChanged
			);
			if (eventType == TurnEventType.EXPLAIN_CURRENT_PAGE
				&& nextPageStatus == PageStatus.EXPLAINED) {
				pageRecordRepository.upsertExplainedPage(
					sessionId,
					session.getCurrentPage(),
					clock.instant()
				);
			}
		}

		saveMemoryCandidates(
			userId,
			session.getMaterialId(),
			sessionId,
			userMessageId,
			aiResponse.memoryCandidates()
		);
		sessionRepository.flush();
		messageRepository.flush();

		List<MessageResponse> messages = aiMessages.stream()
			.map(MessageResponse::from)
			.toList();
		return new PersistedTurn(
			aiResponse.turnId(),
			sessionId,
			messages,
			uiActions,
			new TurnStateResponse(
				session.getCurrentPage(),
				session.getPageStatus(),
				session.getActiveQuizId()
			),
			parseMemoryWrite(aiResponse.memoryWrite()),
			session.getMaterialId()
		);
	}

	private boolean isQuizEligible(
		LearningSession session,
		TurnEventType eventType,
		PageStatus finalPageStatus,
		boolean pageStatusChanged
	) {
		if (finalPageStatus != PageStatus.EXPLAINED
			|| !pageStatusChanged) {
			return false;
		}
		if (eventType != TurnEventType.EXPLAIN_CURRENT_PAGE) {
			return true;
		}
		int textLength = materialPageRepository
			.findTextLengthByMaterialIdAndPageNumber(
				session.getMaterialId(),
				session.getCurrentPage()
			)
			.orElse(0);
		return textLength >= quizProperties.proposalMinPageTextLength();
	}

	private List<ChatMessage> saveAiMessages(
		LearningSession session,
		List<Map<String, Object>> values
	) {
		List<ChatMessage> messages = new ArrayList<>();
		for (Map<String, Object> value : values) {
			MessageType type = MessageType.valueOf(
				(String) value.get("messageType")
			);
			ChatMessage message = messageRepository.save(
				ChatMessage.ai(session, type, (String) value.get("content"))
			);
			messages.add(message);
		}
		messageRepository.flush();
		return List.copyOf(messages);
	}

	private void applyQaThread(
		LearningSession session,
		TurnEventType eventType,
		Long userMessageId,
		List<ChatMessage> aiMessages,
		Map<String, Object> patch
	) {
		Object rawQaThread = patch.get("qaThread");
		if (rawQaThread == null) {
			if (eventType == TurnEventType.USER_QUESTION) {
				throw policy();
			}
			return;
		}
		if (eventType != TurnEventType.USER_QUESTION
			&& eventType != TurnEventType.EXPLAIN_CURRENT_PAGE) {
			throw policy();
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> qaPatch = (Map<String, Object>) rawQaThread;
		String mode = (String) qaPatch.get("mode");
		QaThread thread;
		if ("START_NEW".equals(mode)) {
			qaThreadRepository
				.findTopBySession_IdAndStatusOrderByUpdatedAtDescIdDesc(
					session.getId(),
					QaThreadStatus.ACTIVE
				)
				.ifPresent(QaThread::close);
			thread = qaThreadRepository.saveAndFlush(QaThread.start(session));
		} else {
			Long threadId = parseThreadRef(
				(String) qaPatch.get("threadRef")
			);
			thread = qaThreadRepository.findActiveForUpdate(
					threadId,
					session.getId()
				)
				.orElseThrow(this::policy);
		}

		ChatMessage userMessage = messageRepository.findById(userMessageId)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.SESSION_STATE_CONFLICT));
		qaMessageRepository.save(QaMessage.from(thread, userMessage));
		aiMessages.stream()
			.filter(message -> message.getMessageType() == MessageType.QA)
			.forEach(message ->
				qaMessageRepository.save(QaMessage.from(thread, message)));
		qaMessageRepository.flush();
	}

	private void validatePendingDiagnosis(
		LearningSession session,
		TurnEventType eventType,
		Map<String, Object> patch
	) {
		if (!patch.containsKey("pendingDiagnosis")) {
			if (eventType == TurnEventType.DIAGNOSIS_ANSWER_SUBMITTED) {
				throw policy();
			}
			return;
		}
		Long value = nullableLong(patch.get("pendingDiagnosis"));
		if (eventType == TurnEventType.DIAGNOSIS_ANSWER_SUBMITTED) {
			if (value != null) {
				throw policy();
			}
			return;
		}
		if (value == null
			|| !value.equals(session.getPendingDiagnosisId())) {
			throw policy();
		}
	}

	private void completeDiagnosis(
		Long diagnosisId,
		List<ChatMessage> messages,
		PageStatus nextPageStatus,
		Map<String, Object> patch
	) {
		List<ChatMessage> repairs = messages.stream()
			.filter(message ->
				message.getMessageType() == MessageType.REPAIR)
			.toList();
		if (diagnosisId == null
			|| repairs.size() != 1
			|| nextPageStatus != PageStatus.REPAIR_COMPLETED
			|| !patch.containsKey("pendingDiagnosis")
			|| patch.get("pendingDiagnosis") != null) {
			throw policy();
		}
		diagnosisService.completeDiagnosis(
			diagnosisId,
			repairs.getFirst().getContent()
		);
	}

	private PageStatus pageStatus(Map<String, Object> patch) {
		Object value = patch.get("pageStatus");
		return value == null ? null : PageStatus.valueOf((String) value);
	}

	private void saveMemoryCandidates(
		Long userId,
		Long materialId,
		Long sessionId,
		Long userMessageId,
		List<Map<String, Object>> values
	) {
		for (Map<String, Object> value : values) {
			candidateRepository.save(LearnerMemoryCandidate.create(
				userRepository.getReferenceById(userId),
				materialRepository.getReferenceById(materialId),
				(String) value.get("type"),
				(String) value.get("content"),
				decimal(value.get("confidence")),
				turnEvidenceRefs(
					value.get("evidence"),
					userMessageId,
					sessionId
				),
				"1.0"
			));
		}
		candidateRepository.flush();
	}

	private List<MemoryEvidenceRef> turnEvidenceRefs(
		Object value,
		Long userMessageId,
		Long sessionId
	) {
		@SuppressWarnings("unchecked")
		List<String> evidence = (List<String>) value;
		return evidence.stream()
			.map(String::trim)
			.map(reference -> new MemoryEvidenceRef(
				"TURN",
				userMessageId,
				sessionId,
				reference
			))
			.toList();
	}

	private MemoryWrite parseMemoryWrite(Map<String, Object> value) {
		if (value == null) {
			return null;
		}
		try {
			return new MemoryWrite(
				stringList(value.get("strengths")),
				stringList(value.get("weaknesses")),
				stringList(value.get("misconceptions")),
				stringList(value.get("explanationPreferences")),
				stringList(value.get("preferredQuizTypes")),
				nullableText(value.get("targetDifficulty")),
				stringList(value.get("nextCoachingGoals")),
				nullableText(value.get("memoryDigest")),
				longList(value.get("candidateIds"))
			);
		} catch (RuntimeException exception) {
			throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
		}
	}

	private List<String> stringList(Object value) {
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException();
		}
		List<String> result = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof String text) || !StringUtils.hasText(text)) {
				throw new IllegalArgumentException();
			}
			result.add(text.trim());
		}
		return List.copyOf(result);
	}

	private List<Long> longList(Object value) {
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException();
		}
		List<Long> result = new ArrayList<>();
		for (Object item : list) {
			Long id = nullableLong(item);
			if (id == null || id < 1) {
				throw new IllegalArgumentException();
			}
			result.add(id);
		}
		return List.copyOf(result);
	}

	private Long parseThreadRef(String threadRef) {
		try {
			return Long.valueOf(threadRef.substring(3));
		} catch (RuntimeException exception) {
			throw policy();
		}
	}

	private String nullableText(Object value) {
		return value instanceof String text ? text : null;
	}

	private Long nullableLong(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
	}

	private BigDecimal decimal(Object value) {
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
	}

	private BusinessException policy() {
		return new BusinessException(ErrorCode.AI_POLICY_REJECTED);
	}
}
