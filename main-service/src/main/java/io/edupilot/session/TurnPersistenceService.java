package io.edupilot.session;

import java.math.BigDecimal;
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
import io.edupilot.memory.LearnerMemoryCandidate;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.memory.MemoryEvidenceRef;
import io.edupilot.memory.MemoryWrite;
import io.edupilot.quiz.QuizService;
import io.edupilot.session.dto.MessageResponse;
import io.edupilot.session.dto.TurnStateResponse;
import io.edupilot.user.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TurnPersistenceService {

	private final LearningSessionRepository sessionRepository;
	private final ChatMessageRepository messageRepository;
	private final QaThreadRepository qaThreadRepository;
	private final QaMessageRepository qaMessageRepository;
	private final LearnerMemoryCandidateRepository candidateRepository;
	private final UserRepository userRepository;
	private final LearningMaterialRepository materialRepository;
	private final QuizService quizService;
	private final DiagnosisService diagnosisService;
	private final ObjectMapper objectMapper;

	public TurnPersistenceService(
		LearningSessionRepository sessionRepository,
		ChatMessageRepository messageRepository,
		QaThreadRepository qaThreadRepository,
		QaMessageRepository qaMessageRepository,
		LearnerMemoryCandidateRepository candidateRepository,
		UserRepository userRepository,
		LearningMaterialRepository materialRepository,
		QuizService quizService,
		DiagnosisService diagnosisService,
		ObjectMapper objectMapper
	) {
		this.sessionRepository = sessionRepository;
		this.messageRepository = messageRepository;
		this.qaThreadRepository = qaThreadRepository;
		this.qaMessageRepository = qaMessageRepository;
		this.candidateRepository = candidateRepository;
		this.userRepository = userRepository;
		this.materialRepository = materialRepository;
		this.quizService = quizService;
		this.diagnosisService = diagnosisService;
		this.objectMapper = objectMapper;
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

		List<UiAction> uiActions = parseUiActions(aiResponse.uiActions());
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
		if (eventType == TurnEventType.QUIZ_TYPE_SELECTED) {
			if (nextPageStatus != null
				&& nextPageStatus != PageStatus.QUIZ_READY) {
				throw policy();
			}
			JsonNode generation = quizGeneration(aiResponse);
			activeQuizId = quizService.createFromGeneration(
				sessionId,
				generation
			);
			session.activateQuiz(activeQuizId, uiActions);
		} else {
			if (aiResponse.statePatch().containsKey("activeQuizId")) {
				throw policy();
			}
			if (eventType == TurnEventType.DIAGNOSIS_ANSWER_SUBMITTED) {
				completeDiagnosis(
					diagnosisId,
					aiMessages,
					nextPageStatus,
					aiResponse.statePatch()
				);
			}
			session.applyAiTurn(nextPageStatus, uiActions);
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
			new io.edupilot.session.dto.TurnResponse(
				aiResponse.turnId(),
				sessionId,
				messages,
				uiActions,
				new TurnStateResponse(
					session.getCurrentPage(),
					session.getPageStatus(),
					session.getActiveQuizId()
				)
			),
			parseMemoryWrite(aiResponse.memoryWrite()),
			session.getMaterialId()
		);
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

	private List<UiAction> parseUiActions(
		List<Map<String, Object>> values
	) {
		return values.stream()
			.map(value -> new UiAction(
				(String) value.get("type"),
				nullableText(value.get("content")),
				nullableText(value.get("yesEvent")),
				nullableText(value.get("noEvent")),
				nullableLong(value.get("diagnosisId"))
			))
			.toList();
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

	private JsonNode quizGeneration(
		io.edupilot.ai.dto.TurnResponse response
	) {
		for (Map<String, Object> action : response.actionsExecuted()) {
			Object rawArtifacts = action.get("artifacts");
			if (!(rawArtifacts instanceof Map<?, ?> artifacts)) {
				continue;
			}
			Object generation = artifacts.get("quizGeneration");
			if (generation != null) {
				return objectMapper.valueToTree(generation);
			}
		}
		throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
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
				List.of(new MemoryEvidenceRef(
					"TURN",
					userMessageId,
					sessionId
				)),
				"1.0"
			));
		}
		candidateRepository.flush();
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
