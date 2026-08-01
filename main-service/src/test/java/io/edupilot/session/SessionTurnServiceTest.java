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
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.memory.LearnerMemoryPromotionService;
import io.edupilot.memory.MemoryWrite;
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

	@Test
	void retryableFailureUsesNewTurnIdAndAlwaysReleases() throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"request-1",
			"질문",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L))
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
	void explainMapsStoredAnswerStyleWhenPayloadOmitsDetailLevel() throws Exception {
		assertStoredStyleMapping(AiAnswerStyle.CONCISE, "NORMAL", "concise-request");
		assertStoredStyleMapping(AiAnswerStyle.NORMAL, "NORMAL", "normal-request");
		assertStoredStyleMapping(AiAnswerStyle.DETAILED, "DETAILED", "detailed-request");
	}

	@Test
	void streamsIntermediateEventsThenPersistsBeforeCompleted() throws Exception {
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
			@SuppressWarnings("unchecked")
			Consumer<TurnStreamEvent> listener = invocation.getArgument(1);
			listener.accept(TurnStreamEvent.status("PLANNING"));
			listener.accept(TurnStreamEvent.contentDelta("답변"));
			return aiResponse(aiRequest.turnId());
		});
		TurnResponse publicResponse = publicResponse();
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
		var order = inOrder(persistenceService, streamService);
		order.verify(persistenceService).persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		);
		order.verify(streamService).complete(
			streamConnection,
			publicResponse
		);
		verify(aiClient, never()).executeTurn(any());
	}

	@Test
	void quizStreamCompletesWithOnlyPersistedActiveQuizId() throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"request-quiz",
			"퀴즈 유형 선택: MCQ",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L))
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
			eq(java.util.Set.of(2, 3, 4))
		);
		verify(streamService).complete(streamConnection, publicResponse);
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
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			turnId,
			"ANSWER_USER_QUESTION",
			List.of(),
			List.of(),
			Map.of(),
			List.of(),
			null,
			List.of(),
			null,
			null
		);
	}

	private io.edupilot.ai.dto.TurnResponse quizAiResponse(String turnId) {
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
			),
			List.of(),
			null,
			null
		);
	}

	private void stubPreparedTurn() {
		when(preparationService.prepare(
			1L,
			100L,
			"request-1",
			"질문",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L))
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

	private TurnRequest quizRequest() throws Exception {
		return new TurnRequest(
			"request-quiz",
			"QUIZ_TYPE_SELECTED",
			objectMapper.readTree("{\"quizType\":\"MCQ\"}")
		);
	}

	@Test
	void downstreamCancellationBeforeCommitSkipsPersistence()
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

		assertThatThrownBy(() -> service().execute(
			1L,
			100L,
			userQuestion()
		)).isInstanceOfSatisfying(
			AiClientException.class,
			exception -> assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_STREAM_INTERRUPTED)
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
		verify(streamService).fail(
			eq(streamConnection),
			any(AiClientException.class)
		);
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
		MemoryWrite memoryWrite = new MemoryWrite(
			List.of("strength"),
			List.of("weakness"),
			List.of("misconception"),
			List.of("preference"),
			List.of("MCQ"),
			"BALANCED",
			List.of("goal"),
			"digest",
			List.of(1L)
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
