package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.diagnosis.DiagnosisService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.quiz.QuizService;
import io.edupilot.user.UserRepository;
import tools.jackson.databind.ObjectMapper;

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
			new ObjectMapper()
		);
	}
}
