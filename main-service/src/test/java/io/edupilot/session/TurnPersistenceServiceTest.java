package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.diagnosis.DiagnosisService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.quiz.QuizService;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class TurnPersistenceServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;
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
	void createsOnlyW3ForTheFinalExplainedTransition() {
		LearningSession session = activeSession(
			PageStatus.EXPLAINING,
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

		assertThat(persisted.uiActions())
			.containsExactly(UiAction.quizProposal());
		verify(session).applyAiTurn(
			PageStatus.EXPLAINED,
			List.of(UiAction.quizProposal()),
			true
		);
	}

	@Test
	void returnsNoWidgetAndPreservesStoredWidgetWithoutTransition() {
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
		assertThat(persisted.state().activeQuizId()).isEqualTo(50L);
		assertThat(persisted.state().pageStatus())
			.isEqualTo(PageStatus.QUIZ_READY);
		assertThat(persisted.uiActions()).isEmpty();
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
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-1",
			"EXPLAIN",
			List.of(),
			messages,
			patch,
			List.of(),
			null,
			List.of(),
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
			messageRepository,
			qaThreadRepository,
			qaMessageRepository,
			candidateRepository,
			userRepository,
			materialRepository,
			quizService,
			diagnosisService,
			new UiActionResolver()
		);
	}
}
