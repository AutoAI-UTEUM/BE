package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.diagnosis.Diagnosis;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.diagnosis.DiagnosisStatus;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@ExtendWith(MockitoExtension.class)
class TurnPreparationServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private ChatMessageRepository messageRepository;
	@Mock
	private DiagnosisRepository diagnosisRepository;
	@Mock
	private LearningSession session;
	@Mock
	private Diagnosis diagnosis;

	@Test
	void acceptsSameAnsweredValueForRecoveryWithNewRequestId() {
		givenAnsweredDiagnosis();
		ChatMessage saved = org.mockito.Mockito.mock(ChatMessage.class);
		when(saved.getId()).thenReturn(501L);
		when(messageRepository.saveAndFlush(
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(saved);

		PreparedTurn prepared = service().prepare(
			1L,
			100L,
			"new-request",
			" 같은 답변 ",
			30L
		);

		assertThat(prepared.userMessageId()).isEqualTo(501L);
		verify(diagnosis, never()).answer(
			org.mockito.ArgumentMatchers.anyString()
		);
	}

	@Test
	void rejectsDifferentAnswerAfterDiagnosisWasAnswered() {
		givenAnsweredDiagnosis();

		assertThatThrownBy(() -> service().prepare(
			1L,
			100L,
			"new-request",
			"다른 답변",
			30L
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.DIAGNOSIS_NOT_PENDING)
			);
		verify(messageRepository, never()).saveAndFlush(
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void rejectsQuizSelectionWhileDiagnosisIsPending() {
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		when(session.getActiveTurnRequestId()).thenReturn("request-1");
		when(session.getPageStatus())
			.thenReturn(PageStatus.DIAGNOSIS_PENDING);

		assertThatThrownBy(() -> service().assertEventAllowed(
			1L,
			100L,
			"request-1",
			TurnEventType.QUIZ_TYPE_SELECTED
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
		);
		verify(messageRepository, never()).saveAndFlush(
			org.mockito.ArgumentMatchers.any()
		);
	}

	private void givenAnsweredDiagnosis() {
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		when(session.getId()).thenReturn(100L);
		when(session.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(session.getActiveTurnRequestId()).thenReturn("new-request");
		when(session.getPendingDiagnosisId()).thenReturn(30L);
		when(diagnosisRepository.findByIdForUpdate(30L))
			.thenReturn(Optional.of(diagnosis));
		when(diagnosis.getSessionId()).thenReturn(100L);
		when(diagnosis.getUserId()).thenReturn(1L);
		when(diagnosis.getStatus()).thenReturn(DiagnosisStatus.ANSWERED);
		when(diagnosis.getUserAnswer()).thenReturn("같은 답변");
	}

	private TurnPreparationService service() {
		return new TurnPreparationService(
			sessionRepository,
			messageRepository,
			diagnosisRepository
		);
	}
}
