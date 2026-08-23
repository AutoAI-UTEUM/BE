package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiClientProperties;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.ai.dto.NoteDraft;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.memory.LearnerMemoryPromotionService;
import io.edupilot.memory.MemoryWrite;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.quiz.QuizGenerationValidator;
import io.edupilot.session.dto.TurnRequest;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.session.dto.TurnStateResponse;
import io.edupilot.user.AiAnswerStyle;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SessionTurnServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private TurnClaimService claimService;
	@Mock
	private TurnPreparationService preparationService;
	@Mock
	private TurnSnapshotService snapshotService;
	@Mock
	private AiClient aiClient;
	@Mock
	private TurnResponseValidator responseValidator;
	@Mock
	private TurnPersistenceService persistenceService;
	@Mock
	private LearnerMemoryPromotionService memoryPromotionService;
	@Mock
	private SessionStreamService streamService;
	@Mock
	private AiClientProperties aiClientProperties;
	@Mock
	private SessionStreamConnection streamConnection;
	@Mock
	private UserRepository userRepository;
	@Mock
	private MaterialAccessService materialAccessService;

	@Test
	void revokedClassroomMaterialAccessBlocksTurnBeforeClaim() {
		org.mockito.Mockito.doThrow(
			new BusinessException(ErrorCode.MATERIAL_NOT_FOUND)
		).when(materialAccessService).assertSessionAccessible(1L, 100L);

		assertThatThrownBy(() -> service().execute(1L, 100L, userQuestion()))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
		verify(claimService, never()).claim(any(), any(), anyString());
	}

	@Test
	void retryableFailureUsesNewTurnIdAndAlwaysReleases() throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"request-1",
			"질문",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L, true))
			.thenReturn(new TurnSnapshot(
				Map.of("sessionId", 100L),
				Map.of(
					"qaThreadDigest",
					Map.of("threadRef", "qa-30")
				),
				10L
			));
		io.edupilot.ai.dto.TurnResponse aiResponse = aiResponse("ignored");
		when(aiClient.executeTurn(any()))
			.thenThrow(new AiClientException(
				ErrorCode.AI_SERVICE_UNAVAILABLE,
				true,
				null
			))
			.thenAnswer(invocation -> {
				io.edupilot.ai.dto.TurnRequest aiRequest =
					invocation.getArgument(0);
				return aiResponse(aiRequest.turnId());
			});
		TurnResponse publicResponse = new TurnResponse(
			"successful-turn",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(1, PageStatus.EXPLAINED, null)
		);
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(new PersistedTurn(
			publicResponse.turnId(),
			publicResponse.sessionId(),
			publicResponse.messages(),
			publicResponse.uiActions(),
			publicResponse.state(),
			null,
			10L
		));

		TurnResponse actual = service().execute(
			1L,
			100L,
			new TurnRequest(
				"request-1",
				"USER_QUESTION",
				objectMapper.readTree("{\"message\":\"질문\"}")
			)
		);

		assertThat(actual).isEqualTo(publicResponse);
		ArgumentCaptor<io.edupilot.ai.dto.TurnRequest> requests =
			ArgumentCaptor.forClass(
				io.edupilot.ai.dto.TurnRequest.class
			);
		verify(aiClient, org.mockito.Mockito.times(2))
			.executeTurn(requests.capture());
		assertThat(requests.getAllValues())
			.extracting(io.edupilot.ai.dto.TurnRequest::turnId)
			.doesNotHaveDuplicates();
		verify(responseValidator).validate(
			any(),
			eq(requests.getAllValues().get(1).turnId()),
			eq("qa-30"),
			eq(TurnEventType.USER_QUESTION),
			eq((String) null),
			eq(java.util.Set.of())
		);
		verify(claimService).claim(1L, 100L, "request-1");
		verify(claimService).release(100L, "request-1");
	}

	@Test
	void rejectsUnknownEventAndMalformedPayloadBeforeClaim()
		throws Exception {
		assertError(
			() -> service().execute(
				1L,
				100L,
				new TurnRequest(
					"request-1",
					"UNKNOWN",
					objectMapper.createObjectNode()
				)
			),
			ErrorCode.UNSUPPORTED_EVENT_TYPE
		);
		assertError(
			() -> service().execute(
				1L,
				100L,
				new TurnRequest(
					"request-2",
					"USER_QUESTION",
					objectMapper.createObjectNode()
				)
			),
			ErrorCode.VALIDATION_FAILED
		);
		verify(claimService, never()).claim(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyLong(),
			anyString()
		);
	}

	@Test
	void noteRequestedUsesEmptyPayloadAndReturnsDraft() throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"request-note",
			"노트 작성 요청",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L, true))
			.thenReturn(new TurnSnapshot(
				Map.of("sessionId", 100L),
				Map.of("currentPageText", "페이지 내용"),
				10L
			));
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.empty());
		NoteDraft aiDraft = new NoteDraft("복습 노트", "## 핵심\n내용");
		when(aiClient.executeTurn(any())).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			return new io.edupilot.ai.dto.TurnResponse(
				"1.0",
				aiRequest.turnId(),
				"WRITE_NOTE",
				List.of(),
				List.of(),
				Map.of(),
				List.of(),
				null,
				List.of(),
				null,
				aiDraft,
				null
			);
		});
		io.edupilot.session.dto.NoteDraft publicDraft =
			new io.edupilot.session.dto.NoteDraft("복습 노트", "## 핵심\n내용");
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(new PersistedTurn(
			"turn-note",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(1, PageStatus.EXPLAINED, null),
			publicDraft,
			null,
			10L
		));

		TurnResponse result = service().execute(
			1L,
			100L,
			new TurnRequest(
				"request-note",
				"NOTE_REQUESTED",
				objectMapper.createObjectNode()
			)
		);

		assertThat(result.noteDraft()).isEqualTo(publicDraft);
		ArgumentCaptor<io.edupilot.ai.dto.TurnRequest> requestCaptor =
			ArgumentCaptor.forClass(io.edupilot.ai.dto.TurnRequest.class);
		verify(aiClient).executeTurn(requestCaptor.capture());
		assertThat(requestCaptor.getValue().event())
			.containsEntry("eventType", "NOTE_REQUESTED")
			.containsEntry("payload", Map.of());
		verify(responseValidator).validate(
			any(),
			anyString(),
			eq((String) null),
			eq(TurnEventType.NOTE_REQUESTED),
			eq((String) null),
			eq(java.util.Set.of())
		);
	}

	@Test
	void noteRequestedRejectsPayloadFieldsBeforeClaim() throws Exception {
		assertError(
			() -> service().execute(
				1L,
				100L,
				new TurnRequest(
					"request-note",
					"NOTE_REQUESTED",
					objectMapper.readTree("{\"message\":\"노트\"}")
				)
			),
			ErrorCode.VALIDATION_FAILED
		);

		verify(claimService, never()).claim(any(), any(), anyString());
		verify(aiClient, never()).executeTurn(any());
	}

	@Test
	void userQuestionPropagatesDefaultExplicitTrueAndFalsePageContextFlag()
		throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"request-1",
			"question",
			null
		)).thenReturn(new PreparedTurn(501L));
		TurnSnapshot snapshot = new TurnSnapshot(
			Map.of("sessionId", 100L),
			Map.of(),
			10L
		);
		when(snapshotService.build(1L, 100L, 501L, true))
			.thenReturn(snapshot);
		when(snapshotService.build(1L, 100L, 501L, false))
			.thenReturn(snapshot);
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.empty());
		when(aiClient.executeTurn(any())).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			return aiResponse(aiRequest.turnId());
		});
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(persisted(publicResponse()));

		service().execute(1L, 100L, questionPayload(""));
		service().execute(
			1L,
			100L,
			questionPayload(",\"includeCurrentPage\":true")
		);
		service().execute(
			1L,
			100L,
			questionPayload(",\"includeCurrentPage\":false")
		);

		verify(snapshotService, org.mockito.Mockito.times(2))
			.build(1L, 100L, 501L, true);
		verify(snapshotService).build(1L, 100L, 501L, false);
		ArgumentCaptor<io.edupilot.ai.dto.TurnRequest> requests =
			ArgumentCaptor.forClass(io.edupilot.ai.dto.TurnRequest.class);
		verify(aiClient, org.mockito.Mockito.times(3))
			.executeTurn(requests.capture());
		assertThat(requests.getAllValues())
			.extracting(request -> request.event().get("payload"))
			.containsExactly(
				Map.of("message", "question"),
				Map.of("message", "question"),
				Map.of(
					"message", "question",
					"includeCurrentPage", false
				)
			);
	}

	@Test
	void rejectsInvalidUserIncludeCurrentPageBeforeClaim()
		throws Exception {
		assertError(
			() -> service().execute(
				1L,
				100L,
				questionPayload(",\"includeCurrentPage\":null")
			),
			ErrorCode.VALIDATION_FAILED
		);
		assertError(
			() -> service().execute(
				1L,
				100L,
				questionPayload(",\"includeCurrentPage\":\"false\"")
			),
			ErrorCode.VALIDATION_FAILED
		);
		verify(claimService, never()).claim(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyLong(),
			anyString()
		);
	}

	@Test
	void rejectsExplainIncludeCurrentPageBeforeNormalization()
		throws Exception {
		assertError(
			() -> service().execute(
				1L,
				100L,
				new TurnRequest(
					"request-1",
					"EXPLAIN_CURRENT_PAGE",
					objectMapper.readTree(
						"{\"includeCurrentPage\":false}"
					)
				)
			),
			ErrorCode.VALIDATION_FAILED
		);

		verify(claimService, never()).claim(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyLong(),
			anyString()
		);
		verify(aiClient, never()).executeTurn(any());
		verify(aiClient, never()).executeTurnStream(
			any(),
			any(),
			any(),
			any()
		);
	}

	@Test
	void explainUsesPayloadDetailLevelBeforeStoredPreference() throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"explain-request",
			"현재 페이지 설명 요청: DETAILED",
			null
		)).thenThrow(new BusinessException(ErrorCode.SESSION_STATE_CONFLICT));

		assertError(
			() -> service().execute(
				1L,
				100L,
				new TurnRequest(
					"explain-request",
					"EXPLAIN_CURRENT_PAGE",
					objectMapper.readTree("{\"detailLevel\":\"DETAILED\"}")
				)
			),
			ErrorCode.SESSION_STATE_CONFLICT
		);

		verify(userRepository, never()).findById(any());
	}

	@Test
	void rejectsUnsupportedDetailLevelBeforeClaim() throws Exception {
		assertError(
			() -> service().execute(
				1L,
				100L,
				new TurnRequest(
					"invalid-detail-request",
					"EXPLAIN_CURRENT_PAGE",
					objectMapper.readTree("{\"detailLevel\":\"BRIEF\"}")
				)
			),
			ErrorCode.VALIDATION_FAILED
		);

		verify(claimService, never()).claim(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyLong(),
			anyString()
		);
	}

	@Test
	void explainMapsStoredAnswerStyleWhenPayloadOmitsDetailLevel() throws Exception {
		assertStoredStyleMapping(AiAnswerStyle.CONCISE, "NORMAL", "concise-request");
		assertStoredStyleMapping(AiAnswerStyle.NORMAL, "NORMAL", "normal-request");
		assertStoredStyleMapping(AiAnswerStyle.DETAILED, "DETAILED", "detailed-request");
	}

	@Test
	void explainSendsResolvedDefaultAndExplicitDetailLevel() throws Exception {
		User user = User.create("user@example.com", "hash", "학습자");
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		when(preparationService.prepare(
			1L,
			100L,
			"default-request",
			"현재 페이지 설명 요청: NORMAL",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(preparationService.prepare(
			1L,
			100L,
			"explicit-request",
			"현재 페이지 설명 요청: DETAILED",
			null
		)).thenReturn(new PreparedTurn(501L));
		TurnSnapshot snapshot = new TurnSnapshot(
			Map.of("sessionId", 100L),
			Map.of(),
			10L
		);
		when(snapshotService.build(1L, 100L, 501L, true))
			.thenReturn(snapshot);
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.empty());
		when(aiClient.executeTurn(any())).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			return aiResponse(aiRequest.turnId());
		});
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(persisted(publicResponse()));

		service().execute(
			1L,
			100L,
			new TurnRequest(
				"default-request",
				"EXPLAIN_CURRENT_PAGE",
				objectMapper.createObjectNode()
			)
		);
		service().execute(
			1L,
			100L,
			new TurnRequest(
				"explicit-request",
				"EXPLAIN_CURRENT_PAGE",
				objectMapper.readTree("{\"detailLevel\":\"DETAILED\"}")
			)
		);

		ArgumentCaptor<io.edupilot.ai.dto.TurnRequest> requests =
			ArgumentCaptor.forClass(io.edupilot.ai.dto.TurnRequest.class);
		verify(aiClient, org.mockito.Mockito.times(2))
			.executeTurn(requests.capture());
		assertThat(requests.getAllValues())
			.extracting(request -> request.event().get("payload"))
			.containsExactly(
				Map.of("detailLevel", "NORMAL"),
				Map.of("detailLevel", "DETAILED")
			);
	}

	@Test
	void quizAndDiagnosisSendExactNormalizedPayloads() throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"quiz-request",
			"퀴즈 유형 선택: MCQ",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(preparationService.prepare(
			1L,
			100L,
			"diagnosis-request",
			"answer",
			30L
		)).thenReturn(new PreparedTurn(501L));
		TurnSnapshot snapshot = new TurnSnapshot(
			Map.of("sessionId", 100L, "currentPage", 2),
			Map.of("currentPageText", "현재"),
			10L
		);
		when(snapshotService.build(1L, 100L, 501L, true))
			.thenReturn(snapshot);
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.empty());
		when(aiClient.executeTurn(any())).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			return aiResponse(aiRequest.turnId());
		});
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(persisted(publicResponse()));

		service().execute(
			1L,
			100L,
			new TurnRequest(
				"quiz-request",
				"QUIZ_TYPE_SELECTED",
				objectMapper.readTree("{\"quizType\":\" MCQ \"}")
			)
		);
		service().execute(
			1L,
			100L,
			new TurnRequest(
				"diagnosis-request",
				"DIAGNOSIS_ANSWER_SUBMITTED",
				objectMapper.readTree(
					"{\"diagnosisId\":30,\"answer\":\" answer \"}"
				)
			)
		);

		ArgumentCaptor<io.edupilot.ai.dto.TurnRequest> requests =
			ArgumentCaptor.forClass(io.edupilot.ai.dto.TurnRequest.class);
		verify(aiClient, org.mockito.Mockito.times(2))
			.executeTurn(requests.capture());
		assertThat(requests.getAllValues())
			.extracting(request -> request.event().get("payload"))
			.containsExactly(
				Map.of("quizType", "MCQ"),
				Map.of("diagnosisId", 30L, "answer", "answer")
			);
	}

	@Test
	void streamsIntermediateEventsThenPersistsBeforeCompleted() throws Exception {
		stubPreparedTurn();
		Map<String, Object> moveNextPageProposal = Map.of(
			"type", "BINARY_DECISION",
			"content", "AI 임의 문구",
			"yesEvent", "MOVE_NEXT_PAGE",
			"noEvent", "WAIT"
		);
		when(aiClientProperties.turnReadTimeout())
			.thenReturn(Duration.ofSeconds(200));
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.of(streamConnection));
		when(aiClient.executeTurnStream(
			any(),
			any(),
			any(),
			any()
		)).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			assertThat(aiRequest.event().get("payload"))
				.isEqualTo(Map.of("message", "질문"));
			@SuppressWarnings("unchecked")
			Consumer<TurnStreamEvent> listener = invocation.getArgument(1);
			listener.accept(TurnStreamEvent.status("PLANNING"));
			listener.accept(TurnStreamEvent.contentDelta("답변"));
			return aiResponse(
				aiRequest.turnId(),
				List.of(moveNextPageProposal)
			);
		});
		TurnResponse publicResponse = new TurnResponse(
			"successful-turn",
			100L,
			List.of(),
			List.of(UiAction.moveNextPage()),
			new TurnStateResponse(1, PageStatus.EXPLAINED, null)
		);
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(persisted(publicResponse));

		TurnResponse actual = service().execute(
			1L,
			100L,
			userQuestion()
		);

		assertThat(actual).isEqualTo(publicResponse);
		verify(streamConnection).send(TurnStreamEvent.status("PLANNING"));
		verify(streamConnection).send(TurnStreamEvent.contentDelta("답변"));
		ArgumentCaptor<io.edupilot.ai.dto.TurnResponse> aiResponseCaptor =
			ArgumentCaptor.forClass(io.edupilot.ai.dto.TurnResponse.class);
		var order = inOrder(persistenceService, streamService);
		order.verify(persistenceService).persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			aiResponseCaptor.capture()
		);
		order.verify(streamService).complete(
			streamConnection,
			publicResponse
		);
		assertThat(aiResponseCaptor.getValue().uiActions())
			.containsExactly(moveNextPageProposal);
		verify(aiClient, never()).executeTurn(any());
	}

	@Test
	void quizStreamAllowsOnlyCurrentPageWhenAdjacentTextsExist()
		throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"request-quiz",
			"퀴즈 유형 선택: MCQ",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L, true))
			.thenReturn(new TurnSnapshot(
				Map.of("sessionId", 100L, "currentPage", 3),
				Map.of(
					"previousPageText", "이전",
					"currentPageText", "현재",
					"nextPageText", "다음"
				),
				10L
			));
		when(aiClientProperties.turnReadTimeout())
			.thenReturn(Duration.ofSeconds(200));
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.of(streamConnection));
		when(aiClient.executeTurnStream(
			any(),
			any(),
			any(),
			any()
		)).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			return quizAiResponse(aiRequest.turnId());
		});
		TurnResponse publicResponse = new TurnResponse(
			"successful-quiz-turn",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(3, PageStatus.QUIZ_READY, 50L)
		);
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(persisted(publicResponse));

		TurnResponse actual = service().execute(
			1L,
			100L,
			quizRequest()
		);

		assertThat(actual.state().activeQuizId()).isEqualTo(50L);
		assertThat(objectMapper.writeValueAsString(actual))
			.doesNotContain("answerChoiceId")
			.doesNotContain("explanation")
			.doesNotContain("\"quiz\"");
		verify(streamConnection, never()).send(any());
		verify(responseValidator).validate(
			any(),
			anyString(),
			eq((String) null),
			eq(TurnEventType.QUIZ_TYPE_SELECTED),
			eq("MCQ"),
			eq(java.util.Set.of(3))
		);
		verify(streamService).complete(streamConnection, publicResponse);
	}

	@Test
	void quizWithoutCurrentPageTextAllowsNoPages() throws Exception {
		stubQuizTurn(
			new TurnSnapshot(
				Map.of("sessionId", 100L, "currentPage", 3),
				Map.of(
					"previousPageText", "previous",
					"nextPageText", "next"
				),
				10L
			),
			new QuizGeneration.Coverage(3, 3)
		);
		TurnResponse publicResponse = new TurnResponse(
			"successful-quiz-turn",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(3, PageStatus.QUIZ_READY, 50L)
		);
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(persisted(publicResponse));

		assertThat(service().execute(1L, 100L, quizRequest()))
			.isEqualTo(publicResponse);

		verify(responseValidator).validate(
			any(),
			anyString(),
			eq((String) null),
			eq(TurnEventType.QUIZ_TYPE_SELECTED),
			eq("MCQ"),
			eq(java.util.Set.of())
		);
	}

	@Test
	void quizWithNonNumericCurrentPageKeepsInvalidResponseError()
		throws Exception {
		stubQuizTurn(
			new TurnSnapshot(
				Map.of("sessionId", 100L, "currentPage", "3"),
				Map.of("currentPageText", "current"),
				10L
			),
			new QuizGeneration.Coverage(3, 3)
		);

		assertThatThrownBy(() -> service().execute(
			1L,
			100L,
			quizRequest()
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
		);

		verify(responseValidator, never()).validate(
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		);
	}

	@Test
	void quizValidatorRejectsPreviousPageCoverage() throws Exception {
		stubQuizTurn(
			new TurnSnapshot(
				Map.of("sessionId", 100L, "currentPage", 3),
				Map.of(
					"previousPageText", "previous",
					"currentPageText", "current",
					"nextPageText", "next"
				),
				10L
			),
			new QuizGeneration.Coverage(2, 2)
		);
		TurnResponseValidator actualValidator = new TurnResponseValidator(
			new QuizGenerationValidator()
		);
		org.mockito.Mockito.doAnswer(invocation -> {
			actualValidator.validate(
				invocation.getArgument(0),
				invocation.getArgument(1),
				invocation.getArgument(2),
				invocation.getArgument(3),
				invocation.getArgument(4),
				invocation.getArgument(5)
			);
			return null;
		}).when(responseValidator).validate(
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		);

		assertThatThrownBy(() -> service().execute(
			1L,
			100L,
			quizRequest()
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
		);

		verify(responseValidator).validate(
			any(),
			anyString(),
			eq((String) null),
			eq(TurnEventType.QUIZ_TYPE_SELECTED),
			eq("MCQ"),
			eq(java.util.Set.of(3))
		);
		verify(persistenceService, never()).persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		);
	}

	@Test
	void retriesStreamOnlyBeforeContentDelta() throws Exception {
		stubPreparedTurn();
		when(aiClientProperties.turnReadTimeout())
			.thenReturn(Duration.ofSeconds(200));
		AtomicLong clock = new AtomicLong();
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.of(streamConnection));
		when(aiClient.executeTurnStream(
			any(),
			any(),
			any(),
			any()
		))
			.thenAnswer(invocation -> {
				clock.addAndGet(Duration.ofSeconds(50).toNanos());
				throw new AiClientException(
					ErrorCode.AI_SERVICE_UNAVAILABLE,
					true,
					null
				);
			})
			.thenAnswer(invocation -> {
				io.edupilot.ai.dto.TurnRequest aiRequest =
					invocation.getArgument(0);
				return aiResponse(aiRequest.turnId());
			});
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(persisted(publicResponse()));

		service(clock::get).execute(1L, 100L, userQuestion());

		ArgumentCaptor<Duration> budgets =
			ArgumentCaptor.forClass(Duration.class);
		verify(aiClient, org.mockito.Mockito.times(2)).executeTurnStream(
			any(),
			any(),
			any(),
			budgets.capture()
		);
		assertThat(budgets.getAllValues()).containsExactly(
			Duration.ofSeconds(200),
			Duration.ofSeconds(150)
		);
	}

	@Test
	void doesNotRetryStreamAfterContentDelta() throws Exception {
		stubPreparedTurn();
		when(aiClientProperties.turnReadTimeout())
			.thenReturn(Duration.ofSeconds(200));
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.of(streamConnection));
		when(aiClient.executeTurnStream(
			any(),
			any(),
			any(),
			any()
		)).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			Consumer<TurnStreamEvent> listener = invocation.getArgument(1);
			listener.accept(TurnStreamEvent.contentDelta("일부"));
			throw new AiClientException(
				ErrorCode.AI_SERVICE_UNAVAILABLE,
				true,
				null
			);
		});

		assertThatThrownBy(() -> service().execute(
			1L,
			100L,
			userQuestion()
		)).isInstanceOf(AiClientException.class);

		verify(aiClient).executeTurnStream(any(), any(), any(), any());
		verify(streamService).fail(
			eq(streamConnection),
			any(AiClientException.class)
		);
		verify(persistenceService, never()).persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		);
		verify(preparationService).markFailed(501L);
	}

	@Test
	void schemaRejectionMarksUserMessageFailedWithoutPersistence()
		throws Exception {
		stubPreparedTurn();
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.empty());
		when(aiClient.executeTurn(any())).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			return aiResponse(aiRequest.turnId());
		});
		AiClientException rejection = new AiClientException(
			ErrorCode.AI_RESPONSE_INVALID,
			false,
			null
		);
		org.mockito.Mockito.doThrow(rejection)
			.when(responseValidator).validate(
				any(),
				anyString(),
				any(),
				any(),
				any(),
				any()
			);

		assertThatThrownBy(() -> service().execute(
			1L,
			100L,
			userQuestion()
		)).isSameAs(rejection);

		verify(preparationService).markFailed(501L);
		verify(persistenceService, never()).persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		);
		verify(claimService).release(100L, "request-1");
	}

	@Test
	void compensationFailureDoesNotHideOriginalTurnFailure()
		throws Exception {
		stubPreparedTurn();
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.empty());
		AiClientException original = new AiClientException(
			ErrorCode.AI_SERVICE_UNAVAILABLE,
			false,
			null
		);
		when(aiClient.executeTurn(any())).thenThrow(original);
		org.mockito.Mockito.doThrow(new IllegalStateException("mark failed"))
			.when(preparationService).markFailed(501L);

		assertThatThrownBy(() -> service().execute(
			1L,
			100L,
			userQuestion()
		)).isSameAs(original);
		verify(claimService).release(100L, "request-1");
	}

	private SessionTurnService service() {
		return service(System::nanoTime);
	}

	private SessionTurnService service(LongSupplier nanoTime) {
		return new SessionTurnService(
			claimService,
			preparationService,
			snapshotService,
			aiClient,
			responseValidator,
			persistenceService,
			memoryPromotionService,
			streamService,
			aiClientProperties,
			userRepository,
			materialAccessService,
			nanoTime
		);
	}

	private void assertStoredStyleMapping(
		AiAnswerStyle style,
		String expectedDetailLevel,
		String requestId
	) throws Exception {
		User user = User.create("user@example.com", "hash", "학습자");
		user.updatePreferences(null, null, style);
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		when(preparationService.prepare(
			1L,
			100L,
			requestId,
			"현재 페이지 설명 요청: " + expectedDetailLevel,
			null
		)).thenThrow(new BusinessException(ErrorCode.SESSION_STATE_CONFLICT));

		assertError(
			() -> service().execute(
				1L,
				100L,
				new TurnRequest(
					requestId,
					"EXPLAIN_CURRENT_PAGE",
					objectMapper.createObjectNode()
				)
			),
			ErrorCode.SESSION_STATE_CONFLICT
		);
	}

	private io.edupilot.ai.dto.TurnResponse aiResponse(String turnId) {
		return aiResponse(turnId, List.of());
	}

	private io.edupilot.ai.dto.TurnResponse aiResponse(
		String turnId,
		List<Map<String, Object>> uiActions
	) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			turnId,
			"ANSWER_USER_QUESTION",
			List.of(),
			List.of(),
			Map.of(),
			uiActions,
			null,
			List.of(),
			null,
			null
		);
	}

	private io.edupilot.ai.dto.TurnResponse quizAiResponse(String turnId) {
		return quizAiResponse(
			turnId,
			new QuizGeneration.Coverage(2, 4)
		);
	}

	private io.edupilot.ai.dto.TurnResponse quizAiResponse(
		String turnId,
		QuizGeneration.Coverage coverage
	) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			turnId,
			"GENERATE_QUIZ",
			List.of(),
			List.of(),
			Map.of("pageStatus", "QUIZ_READY"),
			List.of(),
			new QuizGeneration(
				"1.0",
				"generation-1",
				"MCQ",
				coverage,
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
			),
			List.of(),
			null,
			null
		);
	}

	private void stubQuizTurn(
		TurnSnapshot snapshot,
		QuizGeneration.Coverage coverage
	) throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"request-quiz",
			"퀴즈 유형 선택: MCQ",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L, true))
			.thenReturn(snapshot);
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.empty());
		when(aiClient.executeTurn(any())).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			return quizAiResponse(aiRequest.turnId(), coverage);
		});
	}

	private void stubPreparedTurn() {
		when(preparationService.prepare(
			1L,
			100L,
			"request-1",
			"질문",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L, true))
			.thenReturn(new TurnSnapshot(
				Map.of("sessionId", 100L),
				Map.of(),
				10L
			));
	}

	private TurnRequest userQuestion() throws Exception {
		return new TurnRequest(
			"request-1",
			"USER_QUESTION",
			objectMapper.readTree("{\"message\":\"질문\"}")
		);
	}

	private TurnRequest questionPayload(String extraPayload) {
		try {
			return new TurnRequest(
				"request-1",
				"USER_QUESTION",
				objectMapper.readTree(
					"{\"message\":\" question \"" + extraPayload + "}"
				)
			);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private TurnRequest quizRequest() throws Exception {
		return new TurnRequest(
			"request-quiz",
			"QUIZ_TYPE_SELECTED",
			objectMapper.readTree("{\"quizType\":\"MCQ\"}")
		);
	}

	@Test
	void completedAiResponsePersistsDespiteDownstreamCancellation()
		throws Exception {
		stubPreparedTurn();
		when(aiClientProperties.turnReadTimeout())
			.thenReturn(Duration.ofSeconds(200));
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.of(streamConnection));
		when(aiClient.executeTurnStream(
			any(),
			any(),
			any(),
			any()
		)).thenAnswer(invocation -> {
			AiStreamCancellation cancellation = invocation.getArgument(2);
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			cancellation.cancel();
			return aiResponse(aiRequest.turnId());
		});

		TurnResponse response = publicResponse();
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(persisted(response));

		assertThat(service().execute(1L, 100L, userQuestion()))
			.isEqualTo(response);
		verify(persistenceService).persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		);
		verify(streamService).complete(streamConnection, response);
		verify(streamService, never()).fail(any(), any());
	}

	@Test
	void sseCompletionFailureDoesNotFailPersistedTurn() throws Exception {
		stubPreparedTurn();
		when(aiClientProperties.turnReadTimeout())
			.thenReturn(Duration.ofSeconds(200));
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.of(streamConnection));
		when(aiClient.executeTurnStream(any(), any(), any(), any()))
			.thenAnswer(invocation -> {
				io.edupilot.ai.dto.TurnRequest aiRequest =
					invocation.getArgument(0);
				return aiResponse(aiRequest.turnId());
			});
		TurnResponse response = publicResponse();
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(persisted(response));
		org.mockito.Mockito.doThrow(new AiClientException(
			ErrorCode.AI_STREAM_INTERRUPTED,
			true,
			null
		)).when(streamService).complete(streamConnection, response);

		assertThat(service().execute(1L, 100L, userQuestion()))
			.isEqualTo(response);
		verify(streamService, never()).fail(any(), any());
		verify(claimService).release(100L, "request-1");
	}

	@Test
	void persistenceFailureEmitsErrorWithoutCompleted() throws Exception {
		stubPreparedTurn();
		when(aiClientProperties.turnReadTimeout())
			.thenReturn(Duration.ofSeconds(200));
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.of(streamConnection));
		when(aiClient.executeTurnStream(
			any(),
			any(),
			any(),
			any()
		)).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			return aiResponse(aiRequest.turnId());
		});
		IllegalStateException failure =
			new IllegalStateException("persistence failed");
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenThrow(failure);

		assertThatThrownBy(() -> service().execute(
			1L,
			100L,
			userQuestion()
		)).isSameAs(failure);
		verify(preparationService).markFailed(501L);
		verify(streamService).fail(streamConnection, failure);
		verify(streamService, never()).complete(any(), any());
	}

	@Test
	void promotionFailureDoesNotFailCommittedTurnAndReleasesClaim()
		throws Exception {
		stubPreparedTurn();
		when(streamService.beginTurn(
			eq(1L),
			eq(100L),
			any(AiStreamCancellation.class)
		)).thenReturn(Optional.empty());
		when(aiClient.executeTurn(any())).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest aiRequest =
				invocation.getArgument(0);
			return aiResponse(aiRequest.turnId());
		});
		TurnResponse response = publicResponse();
		MemoryWrite memoryWrite = new MemoryWrite(List.of(1L));
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(new PersistedTurn(
			response.turnId(),
			response.sessionId(),
			response.messages(),
			response.uiActions(),
			response.state(),
			memoryWrite,
			10L
		));
		when(memoryPromotionService.promoteMemory(
			1L,
			10L,
			memoryWrite
		)).thenThrow(new org.springframework.dao
			.OptimisticLockingFailureException("conflict"));

		assertThat(service().execute(1L, 100L, userQuestion()))
			.isEqualTo(response);
		verify(memoryPromotionService)
			.promoteMemory(1L, 10L, memoryWrite);
		verify(claimService).release(100L, "request-1");
	}

	private TurnResponse publicResponse() {
		return new TurnResponse(
			"successful-turn",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(1, PageStatus.EXPLAINED, null)
		);
	}

	private PersistedTurn persisted(TurnResponse response) {
		return new PersistedTurn(
			response.turnId(),
			response.sessionId(),
			response.messages(),
			response.uiActions(),
			response.state(),
			null,
			10L
		);
	}

	private void assertError(Runnable operation, ErrorCode errorCode) {
		assertThatThrownBy(operation::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}
}
