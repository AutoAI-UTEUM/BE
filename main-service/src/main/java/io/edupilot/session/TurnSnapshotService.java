package io.edupilot.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.assessment.QuizAssessment;
import io.edupilot.assessment.QuizAssessmentRepository;
import io.edupilot.diagnosis.Diagnosis;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.diagnosis.RepairResult;
import io.edupilot.diagnosis.RepairResultRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.material.MaterialPageTextMerger;
import io.edupilot.memory.LearnerMemory;
import io.edupilot.memory.LearnerMemoryCandidate;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.memory.LearnerMemoryRepository;
import io.edupilot.memory.MemoryCandidateStatus;

@Service
public class TurnSnapshotService {

	private static final int PAGE_TEXT_LIMIT = 8_000;
	private static final int MESSAGE_LIMIT = 10;
	private static final int QA_MESSAGE_LIMIT = 6;
	private static final int QA_CONTENT_LIMIT = 500;
	private static final int MEMORY_CANDIDATE_LIMIT = 10;

	private final LearningSessionRepository sessionRepository;
	private final MaterialPageRepository pageRepository;
	private final MaterialPageTextMerger pageTextMerger;
	private final ChatMessageRepository messageRepository;
	private final QaThreadRepository qaThreadRepository;
	private final QaMessageRepository qaMessageRepository;
	private final QuizAssessmentRepository assessmentRepository;
	private final LearnerMemoryRepository memoryRepository;
	private final LearnerMemoryCandidateRepository candidateRepository;
	private final DiagnosisRepository diagnosisRepository;
	private final RepairResultRepository repairRepository;

	public TurnSnapshotService(
		LearningSessionRepository sessionRepository,
		MaterialPageRepository pageRepository,
		MaterialPageTextMerger pageTextMerger,
		ChatMessageRepository messageRepository,
		QaThreadRepository qaThreadRepository,
		QaMessageRepository qaMessageRepository,
		QuizAssessmentRepository assessmentRepository,
		LearnerMemoryRepository memoryRepository,
		LearnerMemoryCandidateRepository candidateRepository,
		DiagnosisRepository diagnosisRepository,
		RepairResultRepository repairRepository
	) {
		this.sessionRepository = sessionRepository;
		this.pageRepository = pageRepository;
		this.pageTextMerger = pageTextMerger;
		this.messageRepository = messageRepository;
		this.qaThreadRepository = qaThreadRepository;
		this.qaMessageRepository = qaMessageRepository;
		this.assessmentRepository = assessmentRepository;
		this.memoryRepository = memoryRepository;
		this.candidateRepository = candidateRepository;
		this.diagnosisRepository = diagnosisRepository;
		this.repairRepository = repairRepository;
	}

	@Transactional(readOnly = true)
	public TurnSnapshot build(
		Long userId,
		Long sessionId,
		Long currentRequestMessageId,
		boolean includeCurrentPage
	) {
		LearningSession session = sessionRepository
			.findByIdAndUser_Id(sessionId, userId)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_NOT_ACTIVE);
		}

		Long materialId = session.getMaterialId();
		LearnerMemory memory = memoryRepository
			.findByUser_IdAndMaterial_Id(userId, materialId)
			.orElse(null);

		Map<String, Object> sessionData = new LinkedHashMap<>();
		sessionData.put("sessionId", sessionId);
		sessionData.put("userId", userId);
		sessionData.put("materialId", materialId);
		sessionData.put("currentPage", session.getCurrentPage());
		sessionData.put("pageStatus", session.getPageStatus().name());

		Map<String, Object> context = new LinkedHashMap<>();
		context.put(
			"currentPageText",
			includeCurrentPage
				? pageText(materialId, session.getCurrentPage())
				: null
		);
		context.put(
			"previousPageText",
			!includeCurrentPage || session.getCurrentPage() == 1
				? null
				: pageText(materialId, session.getCurrentPage() - 1)
		);
		context.put(
			"nextPageText",
			!includeCurrentPage
				|| session.getMaterialPageCount() == null
				|| session.getCurrentPage() >= session.getMaterialPageCount()
				? null
				: pageText(materialId, session.getCurrentPage() + 1)
		);
		context.put(
			"recentMessages",
			recentMessages(
				sessionId,
				currentRequestMessageId,
				session.getConversationResetAt()
			)
		);
		context.put(
			"qaThreadDigest",
			qaThreadDigest(sessionId, session.getConversationResetAt())
		);
		context.put(
			"quizAssessments",
			recentAssessmentsForSession(sessionId)
		);
		context.put(
			"learnerMemoryDigest",
			memory == null ? null : memory.getMemoryDigest()
		);
		context.put(
			"learnerLevel",
			memory == null ? null : memory.getTargetDifficulty()
		);
		context.put(
			"learnerConfidence",
			learnerConfidence(userId, materialId)
		);
		context.put(
			"pendingDiagnosis",
			pendingDiagnosis(sessionId, session.getPendingDiagnosisId())
		);
		context.put(
			"latestRepair",
			latestRepair(sessionId, session.getConversationResetAt())
		);
		context.put(
			"memory",
			Map.of(
				"temporaryCandidates",
				temporaryCandidates(userId, materialId, sessionId)
			)
		);
		return new TurnSnapshot(sessionData, context, materialId);
	}

	private String pageText(Long materialId, int pageNumber) {
		return pageRepository
			.findByMaterial_IdAndPageNumber(materialId, pageNumber)
			.map(page -> truncate(
				pageTextMerger.mergeCaption(
					page.getTextContent(),
					page.getCaption()
				),
				PAGE_TEXT_LIMIT
			))
			.orElse(null);
	}

	private List<Map<String, Object>> recentMessages(
		Long sessionId,
		Long excludedMessageId,
		Instant conversationResetAt
	) {
		List<ChatMessage> messages = new ArrayList<>(
			messageRepository.findRecentContextMessages(
				sessionId,
				PageRequest.of(0, MESSAGE_LIMIT + 1)
			)
		);
		messages.removeIf(message ->
			message.getStatus() == ChatMessageStatus.FAILED
				|| message.getId().equals(excludedMessageId)
				|| isBeforeOrAtReset(
					message.getCreatedAt(),
					conversationResetAt
				));
		if (messages.size() > MESSAGE_LIMIT) {
			messages = new ArrayList<>(messages.subList(0, MESSAGE_LIMIT));
		}
		Collections.reverse(messages);
		return messages.stream()
			.map(message -> {
				Map<String, Object> value = new LinkedHashMap<>();
				value.put("senderType", message.getSenderType().name());
				value.put("messageType", message.getMessageType().name());
				value.put("content", message.getContent());
				value.put("pageNumber", message.getPageNumber());
				return value;
			})
			.toList();
	}

	private Map<String, Object> qaThreadDigest(
		Long sessionId,
		Instant conversationResetAt
	) {
		QaThread thread = qaThreadRepository
			.findTopBySession_IdAndStatusOrderByUpdatedAtDescIdDesc(
				sessionId,
				QaThreadStatus.ACTIVE
			)
			.orElse(null);
		if (thread == null || isBeforeOrAtReset(
		thread.getCreatedAt(),
		conversationResetAt
		)) {
			return null;
		}
		List<QaMessage> messages = new ArrayList<>(
			qaMessageRepository.findByThread_IdOrderByCreatedAtDescIdDesc(
				thread.getId(),
				PageRequest.of(0, QA_MESSAGE_LIMIT)
			)
		);
		Collections.reverse(messages);
		String digest = messages.stream()
			.map(message ->
				message.getSenderType().name()
					+ ": "
					+ truncate(message.getContent(), QA_CONTENT_LIMIT))
			.collect(java.util.stream.Collectors.joining("\n"));
		return Map.of(
			"threadRef",
			"qa-" + thread.getId(),
			"digest",
			digest
		);
	}

	private List<Map<String, Object>> recentAssessmentsForSession(
		Long sessionId
	) {
		return assessmentRepository
			.findTop5BySession_IdOrderByCreatedAtDescIdDesc(sessionId)
			.stream()
			.map(this::assessmentData)
			.toList();
	}

	private String learnerConfidence(Long userId, Long materialId) {
		List<QuizAssessment> assessments =
			assessmentRepository.findRecentByUserAndMaterial(
				userId,
				materialId,
				PageRequest.of(0, 5)
			);
		if (assessments.isEmpty()) {
			return null;
		}
		double passRatio = assessments.stream()
			.filter(QuizAssessment::isPassed)
			.count() / (double) assessments.size();
		if (passRatio < 0.4) {
			return "LOW";
		}
		if (passRatio <= 0.7) {
			return "MEDIUM";
		}
		return "HIGH";
	}

	private Map<String, Object> assessmentData(QuizAssessment assessment) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("assessmentId", assessment.getId());
		value.put("passed", assessment.isPassed());
		value.put(
			"understandingSummary",
			assessment.getAssessment().understandingSummary()
		);
		value.put("strengths", assessment.getAssessment().strengths());
		value.put("weaknesses", assessment.getAssessment().weaknesses());
		value.put(
			"suspectedMisconceptions",
			assessment.getAssessment().suspectedMisconceptions()
		);
		return value;
	}

	private Map<String, Object> pendingDiagnosis(
		Long sessionId,
		Long diagnosisId
	) {
		if (diagnosisId == null) {
			return null;
		}
		Diagnosis diagnosis = diagnosisRepository.findById(diagnosisId)
			.filter(value -> value.getSessionId().equals(sessionId))
			.orElse(null);
		if (diagnosis == null) {
			return null;
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("diagnosisId", diagnosis.getId());
		value.put("prompt", diagnosis.getDiagnosticPrompt());
		value.put("userAnswer", diagnosis.getUserAnswer());
		value.put("status", diagnosis.getStatus().name());
		return value;
	}

	private Map<String, Object> latestRepair(
		Long sessionId,
		Instant conversationResetAt
	) {
		RepairResult repair = repairRepository
			.findTopBySession_IdOrderByCreatedAtDescIdDesc(sessionId)
			.orElse(null);
		if (repair == null || isBeforeOrAtReset(
			repair.getCreatedAt(),
			conversationResetAt
		)) {
			return null;
		}
		return Map.of(
			"diagnosisId",
			repair.getDiagnosisId(),
			"repairContent",
			repair.getRepairContent()
		);
	}

	private List<Map<String, Object>> temporaryCandidates(
		Long userId,
		Long materialId,
		Long sessionId
	) {
		return candidateRepository
			.findByUser_IdAndMaterial_IdAndStatusOrderByCreatedAtDescIdDesc(
				userId,
				materialId,
				MemoryCandidateStatus.CANDIDATE
			)
			.stream()
			.filter(candidate -> candidate.getEvidenceRefs().stream()
				.anyMatch(reference ->
					sessionId.equals(reference.sessionId())))
			.limit(MEMORY_CANDIDATE_LIMIT)
			.map(this::candidateData)
			.toList();
	}

	private Map<String, Object> candidateData(
		LearnerMemoryCandidate candidate
	) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("candidateId", candidate.getId());
		value.put("type", candidate.getCandidateType());
		value.put("content", candidate.getContent());
		value.put("confidence", candidate.getConfidence());
		value.put("evidenceRefs", candidate.getEvidenceRefs());
		return value;
	}

	private String truncate(String value, int maximumLength) {
		if (value == null || value.length() <= maximumLength) {
			return value;
		}
		return value.substring(0, maximumLength);
	}

	private boolean isBeforeOrAtReset(
		Instant createdAt,
		Instant conversationResetAt
	) {
		return conversationResetAt != null
			&& (createdAt == null || !createdAt.isAfter(conversationResetAt));
	}
}
