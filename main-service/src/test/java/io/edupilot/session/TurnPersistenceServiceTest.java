package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.diagnosis.DiagnosisService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.memory.LearnerMemoryCandidate;
import io.edupilot.memory.MemoryCandidateStatus;
import io.edupilot.quiz.QuizProperties;
import io.edupilot.quiz.QuizService;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class TurnPersistenceServiceTest {

	private static final Instant NOW = Instant.parse(
		"2026-08-01T12:00:00Z"
	);

	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private SessionPageRecordRepository pageRecordRepository;
	@Mock
	private ChatMessageRepository messageRepository;
	@Mock
	private QaThreadRepository qaThreadRepository;
	@Mock
	private QaMessageRepository qaMessageRepository;
	@Mock
	private LearnerMemoryCandidateRepository candidateRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private LearningMaterialRepository materialRepository;
	@Mock
	private MaterialPageRepository materialPageRepository;
	@Mock
	private MaterialOverviewRepository materialOverviewRepository;
	@Mock
	private QuizService quizService;
	@Mock
	private DiagnosisService diagnosisService;

	@Test
	void discardsAiResultWhenSessionCompletedDuringCall() {
		LearningSession completed = org.mockito.Mockito.mock(
			LearningSession.class
		);
		when(completed.getStatus()).thenReturn(SessionStatus.COMPLETED);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(completed));

		assertThatThrownBy(() -> service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			new io.edupilot.ai.dto.TurnResponse(
				"1.0",
				"turn-1",
				"EXPLAIN",
				List.of(),
				List.of(Map.of(
					"messageType",
					"EXPLANATION",
					"content",
					"설명"
				)),
				Map.of("pageStatus", "EXPLAINED"),
				List.of(),
				null,
				List.of(),
				null,
				null
			)
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.SESSION_NOT_ACTIVE)
			);
		verify(messageRepository, never()).save(
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void persistsCancelledContentWithoutApplyingTurnArtifacts() {
		LearningSession session = org.mockito.Mockito.mock(
			LearningSession.class
		);
		when(session.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(session.getActiveTurnRequestId()).thenReturn("request-1");
		when(session.getPageStatus()).thenReturn(PageStatus.EXPLAINING);
		when(session.getCurrentPage()).thenReturn(2);
		when(session.getActiveQuizId()).thenReturn(null);
		when(session.getMaterialId()).thenReturn(10L);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		when(messageRepository.save(any())).thenAnswer(invocation ->
			invocation.getArgument(0)
		);

		PersistedTurn persisted = service().persistCancelled(
			1L,
			100L,
			"request-1",
			"turn-partial",
			"중단 전까지 받은 답변"
		);

		assertThat(persisted.turnId()).isEqualTo("turn-partial");
		assertThat(persisted.messages())
			.singleElement()
			.satisfies(message -> {
				assertThat(message.senderType()).isEqualTo(SenderType.AI);
				assertThat(message.messageType()).isEqualTo(MessageType.TEXT);
				assertThat(message.content())
					.isEqualTo("중단 전까지 받은 답변");
			});
		assertThat(persisted.uiActions()).isEmpty();
		assertThat(persisted.memoryWrite()).isNull();
		assertThat(persisted.noteDraft()).isNull();
		assertThat(persisted.state())
			.isEqualTo(new io.edupilot.session.dto.TurnStateResponse(
				2,
				PageStatus.EXPLAINING,
				null
			));
		verify(session, never()).applyAiTurn(
			any(),
			any(),
			org.mockito.ArgumentMatchers.anyBoolean()
		);
		verify(quizService, never()).createFromGeneration(any(), any(), any());
		verify(candidateRepository, never()).save(any());
		verify(qaMessageRepository, never()).save(any());
	}

	@Test
	void createsQuizProposalAtExactTextLengthThreshold() {
		LearningSession session = activeSession(
			PageStatus.NOT_EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L,
			1
		)).thenReturn(Optional.of(200));

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			responseWithUiActions(
				Map.of("pageStatus", "EXPLAINED"),
				List.of(),
				List.of(moveNextPageProposal("AI 임의 문구"))
			)
		);

		assertThat(persisted.uiActions())
			.containsExactly(UiAction.quizProposal());
		verify(session).applyAiTurn(
			PageStatus.EXPLAINED,
			List.of(UiAction.quizProposal()),
			true
		);
		verify(pageRecordRepository).upsertExplainedPage(100L, 1, NOW);
	}

	@Test
	void acceptsCanonicalMoveNextPageProposalForUserQuestion() {
		LearningSession session = activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);
		stubUserQuestionMessage();

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.USER_QUESTION,
			null,
			501L,
			responseWithUiActions(
				Map.of("qaThread", Map.of("mode", "START_NEW")),
				List.of(),
				List.of(moveNextPageProposal("AI 임의 문구"))
			)
		);

		assertThat(persisted.uiActions())
			.containsExactly(UiAction.moveNextPage());
		verify(session).applyAiTurn(
			null,
			List.of(UiAction.moveNextPage()),
			false
		);
	}

	@Test
	void acceptsNoteProposalForUserQuestionUsingAiContent() {
		LearningSession session = activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);
		stubUserQuestionMessage();
		String content = "지금까지 내용을 노트로 정리할까요?";

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.USER_QUESTION,
			null,
			501L,
			responseWithUiActions(
				Map.of("qaThread", Map.of("mode", "START_NEW")),
				List.of(),
				List.of(noteProposal(content))
			)
		);

		assertThat(persisted.uiActions())
			.containsExactly(UiAction.noteProposal(content));
		verify(session).applyAiTurn(
			null,
			List.of(UiAction.noteProposal(content)),
			false
		);
	}

	@Test
	void ignoresUnregisteredNoteWidgetShape() {
		LearningSession session = activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);
		stubUserQuestionMessage();

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.USER_QUESTION,
			null,
			501L,
			responseWithUiActions(
				Map.of("qaThread", Map.of("mode", "START_NEW")),
				List.of(),
				List.of(Map.of(
					"type", "BINARY_DECISION",
					"content", "노트로 정리할까요?",
					"yesEvent", "NOTE_REQUESTED",
					"noEvent", "MOVE_NEXT_PAGE"
				))
			)
		);

		assertThat(persisted.uiActions()).isEmpty();
		verify(session).applyAiTurn(null, List.of(), false);
	}

	@Test
	void dropsMoveNextPageProposalAtLastPageAndWarnsReason() {
		activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			3,
			3
		);
		stubUserQuestionMessage();
		Logger logger = (Logger) LoggerFactory.getLogger(
			TurnResponseValidator.class
		);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-last-page");

		PersistedTurn persisted;
		try {
			persisted = service().persist(
				1L,
				100L,
				"request-1",
				TurnEventType.USER_QUESTION,
				null,
				501L,
				responseWithUiActions(
					Map.of("qaThread", Map.of("mode", "START_NEW")),
					List.of(),
					List.of(moveNextPageProposal("AI 임의 문구"))
				)
			);
		} finally {
			MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(persisted.uiActions()).isEmpty();
		assertThat(appender.list)
			.filteredOn(event -> event.getFormattedMessage().equals(
				"Dropped AI moveNextPage uiAction at last page"
			))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getKeyValuePairs())
					.anySatisfy(pair -> {
						assertThat(pair.key).isEqualTo("reason");
						assertThat(pair.value).isEqualTo("last page");
					});
			});
	}

	@Test
	void offersNextLearningBelowTextLengthThreshold() {
		LearningSession session = activeSession(
			PageStatus.EXPLAINING,
			PageStatus.EXPLAINED,
			2,
			3
		);
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L,
			2
		)).thenReturn(Optional.of(199));

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			response(Map.of("pageStatus", "EXPLAINED"), List.of())
		);

		assertThat(persisted.uiActions())
			.containsExactly(UiAction.moveNextPage());
		verify(session).applyAiTurn(
			PageStatus.EXPLAINED,
			List.of(UiAction.moveNextPage()),
			true
		);
	}

	@Test
	void offersSessionCompletionForIneligibleLastPage() {
		activeSession(
			PageStatus.EXPLAINING,
			PageStatus.EXPLAINED,
			3,
			3
		);
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L,
			3
		)).thenReturn(Optional.of(199));

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			response(Map.of("pageStatus", "EXPLAINED"), List.of())
		);

		assertThat(persisted.uiActions())
			.containsExactly(UiAction.completeSession());
	}

	@Test
	void treatsMissingExtractedPageAsIneligible() {
		activeSession(
			PageStatus.EXPLAINING,
			PageStatus.EXPLAINED,
			1,
			3
		);
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L,
			1
		)).thenReturn(Optional.empty());

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			response(Map.of("pageStatus", "EXPLAINED"), List.of())
		);

		assertThat(persisted.uiActions())
			.containsExactly(UiAction.moveNextPage());
	}

	@Test
	void doesNotReofferQuizWhenExplainedPageIsExplainedAgain() {
		LearningSession session = activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			response(Map.of("pageStatus", "EXPLAINED"), List.of())
		);

		assertThat(persisted.uiActions()).isEmpty();
		verify(session).applyAiTurn(
			PageStatus.EXPLAINED,
			List.of(),
			false
		);
		verify(pageRecordRepository).upsertExplainedPage(100L, 1, NOW);
		verify(materialPageRepository, never())
			.findTextLengthByMaterialIdAndPageNumber(any(), anyInt());
	}

	@Test
	void doesNotRecordExplainedPageForUserQuestion() {
		LearningSession session = activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);
		ChatMessage userMessage = org.mockito.Mockito.mock(ChatMessage.class);
		when(userMessage.getSenderType()).thenReturn(SenderType.USER);
		when(userMessage.getContent()).thenReturn("질문");
		when(messageRepository.findById(501L))
			.thenReturn(Optional.of(userMessage));
		when(qaThreadRepository.saveAndFlush(any())).thenAnswer(invocation ->
			invocation.getArgument(0)
		);
		Map<String, Object> patch = new LinkedHashMap<>();
		patch.put("pageStatus", "EXPLAINED");
		patch.put("qaThread", Map.of("mode", "START_NEW"));

		service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.USER_QUESTION,
			null,
			501L,
			response(patch, List.of())
		);

		verify(session).applyAiTurn(PageStatus.EXPLAINED, List.of(), false);
		verify(pageRecordRepository, never()).upsertExplainedPage(
			any(),
			org.mockito.ArgumentMatchers.anyInt(),
			any()
		);
	}

	@Test
	void diagnosisCompletionCreatesOnlyFinalW7Widget() {
		LearningSession session = activeSession(
			PageStatus.DIAGNOSIS_PENDING,
			PageStatus.REPAIR_COMPLETED,
			3,
			3
		);
		when(messageRepository.save(any())).thenAnswer(invocation ->
			invocation.getArgument(0)
		);
		Map<String, Object> patch = new LinkedHashMap<>();
		patch.put("pageStatus", "REPAIR_COMPLETED");
		patch.put("pendingDiagnosis", null);
		when(diagnosisService.completeDiagnosis(
			org.mockito.ArgumentMatchers.eq(30L),
			org.mockito.ArgumentMatchers.anyString()
		))
			.thenReturn(true);

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.DIAGNOSIS_ANSWER_SUBMITTED,
			30L,
			501L,
			response(
				patch,
				List.of(Map.of(
					"messageType",
					"REPAIR",
					"content",
					"교정 설명"
				))
			)
		);

		assertThat(persisted.uiActions())
			.containsExactly(UiAction.completeSession());
		verify(diagnosisService).completeDiagnosis(30L, "교정 설명");
		verify(session).applyAiTurn(
			PageStatus.REPAIR_COMPLETED,
			List.of(UiAction.completeSession()),
			true
		);
		verify(pageRecordRepository, never()).upsertExplainedPage(
			any(),
			org.mockito.ArgumentMatchers.anyInt(),
			any()
		);
	}

	@Test
	void offPageDiagnosisPreservesCurrentPageStateAndActions() {
		LearningSession session = activeSession(
			PageStatus.NOT_EXPLAINED,
			PageStatus.NOT_EXPLAINED,
			4,
			5
		);
		org.mockito.Mockito.reset(session);
		when(session.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(session.getActiveTurnRequestId()).thenReturn("request-1");
		when(session.getPageStatus()).thenReturn(
			PageStatus.NOT_EXPLAINED,
			PageStatus.NOT_EXPLAINED
		);
		when(session.getCurrentPage()).thenReturn(4);
		when(session.getMaterialId()).thenReturn(10L);
		List<UiAction> currentActions = List.of(UiAction.pageExplanation());
		when(session.getLastUiActions()).thenReturn(currentActions);
		when(messageRepository.save(any())).thenAnswer(invocation ->
			invocation.getArgument(0)
		);
		when(diagnosisService.completeDiagnosis(
			org.mockito.ArgumentMatchers.eq(30L),
			org.mockito.ArgumentMatchers.anyString()
		)).thenReturn(false);
		Map<String, Object> patch = new LinkedHashMap<>();
		patch.put("pageStatus", "REPAIR_COMPLETED");
		patch.put("pendingDiagnosis", null);

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.DIAGNOSIS_ANSWER_SUBMITTED,
			30L,
			501L,
			response(
				patch,
				List.of(Map.of(
					"messageType", "REPAIR",
					"content", "repair"
				))
			)
		);

		assertThat(persisted.uiActions()).isEqualTo(currentActions);
		verify(diagnosisService).completeDiagnosis(30L, "repair");
		verify(session, never()).applyAiTurn(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyBoolean()
		);
	}

	@Test
	void storesTopLevelQuizAndActivatesOnlySpringQuizId() {
		LearningSession session = activeSession(
			PageStatus.EXPLAINED,
			PageStatus.QUIZ_READY,
			3,
			5
		);
		when(session.getActiveQuizId()).thenReturn(50L);
		QuizGeneration generation = mcqGeneration();
		when(quizService.createFromGeneration(
			100L,
			"1.0",
			generation
		)).thenReturn(50L);
		Map<String, Object> patch = new LinkedHashMap<>();
		patch.put("pageStatus", "QUIZ_READY");
		patch.put("activeQuizId", 999L);

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.QUIZ_TYPE_SELECTED,
			null,
			501L,
			new io.edupilot.ai.dto.TurnResponse(
				"1.0",
				"turn-1",
				"GENERATE_QUIZ",
				List.of(),
				List.of(),
				patch,
				List.of(),
				generation,
				List.of(),
				null,
				null
			)
		);

		verify(quizService).createFromGeneration(100L, "1.0", generation);
		verify(session).activateQuiz(50L, List.of());
		verify(pageRecordRepository, never()).upsertExplainedPage(
			any(),
			org.mockito.ArgumentMatchers.anyInt(),
			any()
		);
		assertThat(persisted.state().activeQuizId()).isEqualTo(50L);
		assertThat(persisted.state().pageStatus())
			.isEqualTo(PageStatus.QUIZ_READY);
		assertThat(persisted.uiActions()).isEmpty();
	}

	@Test
	void storesTurnMemoryCandidateWithRawEvidenceAndTraceReferences() {
		activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);
		Map<String, Object> candidate = new LinkedHashMap<>();
		candidate.put("type", "WEAKNESS");
		candidate.put("content", "분수 나눗셈 개념 보완 필요");
		candidate.put("confidence", new BigDecimal("0.80"));
		candidate.put("evidence", List.of("assessment-1", "qa-2"));
		candidate.put("promotionRequested", true);

		service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			response(
				Map.of(),
				List.of(),
				List.of(candidate)
			)
		);

		ArgumentCaptor<LearnerMemoryCandidate> captor =
			ArgumentCaptor.forClass(LearnerMemoryCandidate.class);
		verify(candidateRepository).save(captor.capture());
		LearnerMemoryCandidate saved = captor.getValue();
		assertThat(saved.getStatus())
			.isEqualTo(MemoryCandidateStatus.CANDIDATE);
		assertThat(saved.getCandidateType()).isEqualTo("WEAKNESS");
		assertThat(saved.getContent())
			.isEqualTo("분수 나눗셈 개념 보완 필요");
		assertThat(saved.getConfidence())
			.isEqualByComparingTo("0.80");
		assertThat(saved.getEvidenceRefs())
			.extracting(
				ref -> ref.sourceType(),
				ref -> ref.sourceId(),
				ref -> ref.sessionId(),
				ref -> ref.reference()
			)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(
					"TURN", 501L, 100L, "assessment-1"
				),
				org.assertj.core.groups.Tuple.tuple(
					"TURN", 501L, 100L, "qa-2"
				)
			);
	}

	@Test
	void acceptsCandidateIdsOnlyMemoryWriteAndPreservesTurnResult() {
		LearningSession session = activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			2,
			3
		);
		when(messageRepository.save(any())).thenAnswer(invocation ->
			invocation.getArgument(0)
		);

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			responseWithMemoryWrite(
				Map.of("candidateIds", List.of(11, 12L)),
				List.of(Map.of(
					"messageType", "EXPLANATION",
					"content", "설명 저장"
				))
			)
		);

		assertThat(persisted.memoryWrite().candidateIds())
			.containsExactly(11L, 12L);
		assertThat(persisted.messages())
			.singleElement()
			.satisfies(message -> {
				assertThat(message.messageType())
					.isEqualTo(MessageType.EXPLANATION);
				assertThat(message.content()).isEqualTo("설명 저장");
			});
		assertThat(persisted.state().currentPage()).isEqualTo(2);
		assertThat(persisted.state().pageStatus())
			.isEqualTo(PageStatus.EXPLAINED);
		verify(session).applyAiTurn(null, List.of(), false);
	}

	@Test
	void rejectsLegacyMemoryWriteFields() {
		activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);
		Map<String, Object> legacy = new LinkedHashMap<>();
		legacy.put("strengths", List.of("강점"));
		legacy.put("weaknesses", List.of("약점"));
		legacy.put("misconceptions", List.of("오개념"));
		legacy.put("explanationPreferences", List.of("예시"));
		legacy.put("preferredQuizTypes", List.of("MCQ"));
		legacy.put("nextCoachingGoals", List.of("목표"));
		legacy.put("candidateIds", List.of(1L));

		assertMemoryWriteRejected(legacy);
	}

	@Test
	void rejectsEmptyNonPositiveAndNonIntegerCandidateIds() {
		activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);

		for (Map<String, Object> invalid : List.of(
			Map.<String, Object>of("candidateIds", List.of()),
			Map.<String, Object>of("candidateIds", List.of(-1)),
			Map.<String, Object>of(
				"candidateIds",
				List.of(new BigDecimal("1.5"))
			)
		)) {
			assertMemoryWriteRejected(invalid);
		}
	}

	@Test
	void generalTurnWithoutMemoryWriteRemainsUnaffected() {
		activeSession(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		);

		PersistedTurn persisted = service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			response(Map.of(), List.of())
		);

		assertThat(persisted.memoryWrite()).isNull();
	}

	private void assertMemoryWriteRejected(Map<String, Object> memoryWrite) {
		assertThatThrownBy(() -> service().persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			responseWithMemoryWrite(memoryWrite, List.of())
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
		);
	}

	private LearningSession activeSession(
		PageStatus previousStatus,
		PageStatus persistedStatus,
		int currentPage,
		Integer pageCount
	) {
		LearningSession session =
			org.mockito.Mockito.mock(LearningSession.class);
		when(session.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(session.getActiveTurnRequestId()).thenReturn("request-1");
		when(session.getPageStatus())
			.thenReturn(previousStatus, persistedStatus);
		when(session.getCurrentPage()).thenReturn(currentPage);
		when(session.getMaterialPageCount()).thenReturn(pageCount);
		when(session.getMaterialId()).thenReturn(10L);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		return session;
	}

	private io.edupilot.ai.dto.TurnResponse response(
		Map<String, Object> patch,
		List<Map<String, Object>> messages
	) {
		return response(patch, messages, List.of());
	}

	private io.edupilot.ai.dto.TurnResponse responseWithMemoryWrite(
		Map<String, Object> memoryWrite,
		List<Map<String, Object>> messages
	) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-1",
			"EXPLAIN",
			List.of(),
			messages,
			Map.of(),
			List.of(),
			null,
			List.of(),
			memoryWrite,
			null
		);
	}

	private io.edupilot.ai.dto.TurnResponse responseWithUiActions(
		Map<String, Object> patch,
		List<Map<String, Object>> messages,
		List<Map<String, Object>> uiActions
	) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-1",
			"EXPLAIN",
			List.of(),
			messages,
			patch,
			uiActions,
			null,
			List.of(),
			null,
			null
		);
	}

	private Map<String, Object> moveNextPageProposal(String content) {
		return Map.of(
			"type", "BINARY_DECISION",
			"content", content,
			"yesEvent", "MOVE_NEXT_PAGE",
			"noEvent", "WAIT"
		);
	}

	private Map<String, Object> noteProposal(String content) {
		return Map.of(
			"type", "BINARY_DECISION",
			"content", content,
			"yesEvent", "NOTE_REQUESTED",
			"noEvent", "WAIT"
		);
	}

	private void stubUserQuestionMessage() {
		ChatMessage userMessage = org.mockito.Mockito.mock(ChatMessage.class);
		when(userMessage.getSenderType()).thenReturn(SenderType.USER);
		when(userMessage.getContent()).thenReturn("질문");
		when(messageRepository.findById(501L))
			.thenReturn(Optional.of(userMessage));
		when(qaThreadRepository.saveAndFlush(any())).thenAnswer(invocation ->
			invocation.getArgument(0)
		);
	}

	private io.edupilot.ai.dto.TurnResponse response(
		Map<String, Object> patch,
		List<Map<String, Object>> messages,
		List<Map<String, Object>> memoryCandidates
	) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-1",
			"EXPLAIN",
			List.of(),
			messages,
			patch,
			List.of(),
			null,
			memoryCandidates,
			null,
			null
		);
	}

	private QuizGeneration mcqGeneration() {
		return new QuizGeneration(
			"1.0",
			"generation-1",
			"MCQ",
			new QuizGeneration.Coverage(2, 4),
			"퀴즈",
			5,
			java.util.stream.IntStream.rangeClosed(1, 5)
				.mapToObj(index -> new QuizGeneration.Question(
					"q" + index,
					"문항 " + index,
					new BigDecimal("10.00"),
					List.of(
						new QuizGeneration.Choice("a", "A"),
						new QuizGeneration.Choice("b", "B")
					),
					"a",
					"해설",
					null,
					null,
					null,
					null,
					null
				))
				.toList()
		);
	}

	private TurnPersistenceService service() {
		return new TurnPersistenceService(
			sessionRepository,
			pageRecordRepository,
			messageRepository,
			qaThreadRepository,
			qaMessageRepository,
			candidateRepository,
			userRepository,
			materialRepository,
			quizService,
			new QuizProposalPolicy(
				materialPageRepository,
				materialOverviewRepository,
				new QuizProperties(new BigDecimal("0.6"), 200)
			),
			diagnosisService,
			new UiActionResolver(),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}
}
