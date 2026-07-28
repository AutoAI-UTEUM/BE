package io.edupilot.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.quiz.Quiz;
import io.edupilot.quiz.QuizSubmission;
import io.edupilot.quiz.QuizType;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.PageStatus;
import io.edupilot.session.UiAction;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class DiagnosisServiceTest {

	@Mock
	private DiagnosisRepository diagnosisRepository;

	@Mock
	private RepairResultRepository repairResultRepository;

	@Mock
	private LearningSessionRepository sessionRepository;

	@Test
	void answerTransitionsPendingDiagnosisToAnswered() {
		Fixture fixture = fixture();
		when(diagnosisRepository.findByIdForUpdate(30L))
			.thenReturn(Optional.of(fixture.diagnosis()));
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(fixture.session()));

		service().answer(1L, 100L, 30L, "  답변  ");

		assertThat(fixture.diagnosis().getStatus())
			.isEqualTo(DiagnosisStatus.ANSWERED);
		assertThat(fixture.diagnosis().getUserAnswer()).isEqualTo("답변");
	}

	@Test
	void missingOrOtherSessionDiagnosisIsHiddenAsNotFound() {
		when(diagnosisRepository.findByIdForUpdate(30L))
			.thenReturn(Optional.empty());
		assertError(
			() -> service().answer(1L, 100L, 30L, "답변"),
			ErrorCode.DIAGNOSIS_NOT_FOUND
		);

		Fixture fixture = fixture();
		when(diagnosisRepository.findByIdForUpdate(30L))
			.thenReturn(Optional.of(fixture.diagnosis()));
		assertError(
			() -> service().answer(1L, 999L, 30L, "답변"),
			ErrorCode.DIAGNOSIS_NOT_FOUND
		);
	}

	@Test
	void nonPendingOrMismatchedPendingDiagnosisReturnsConflict() {
		Fixture fixture = fixture();
		ReflectionTestUtils.setField(
			fixture.session(),
			"pendingDiagnosisId",
			31L
		);
		when(diagnosisRepository.findByIdForUpdate(30L))
			.thenReturn(Optional.of(fixture.diagnosis()));
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(fixture.session()));

		assertError(
			() -> service().answer(1L, 100L, 30L, "답변"),
			ErrorCode.DIAGNOSIS_NOT_PENDING
		);
	}

	@Test
	void completeDiagnosisStoresRepairCompletesAndClearsPending() {
		Fixture fixture = fixture();
		fixture.diagnosis().answer("답변");
		when(diagnosisRepository.findByIdForUpdate(30L))
			.thenReturn(Optional.of(fixture.diagnosis()));
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(fixture.session()));

		service().completeDiagnosis(30L, " 짧은 교정 ");

		assertThat(fixture.diagnosis().getStatus())
			.isEqualTo(DiagnosisStatus.COMPLETED);
		assertThat(fixture.session().getPendingDiagnosisId()).isNull();
		assertThat(fixture.session().getPageStatus())
			.isEqualTo(PageStatus.REPAIR_COMPLETED);
		verify(repairResultRepository).save(any(RepairResult.class));
	}

	private DiagnosisService service() {
		return new DiagnosisService(
			diagnosisRepository,
			repairResultRepository,
			sessionRepository
		);
	}

	private Fixture fixture() {
		User user = User.create("user@example.com", "hash", "사용자");
		ReflectionTestUtils.setField(user, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			user,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		LearningSession session = LearningSession.create(user, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		Quiz quiz = Quiz.create(
			session,
			1,
			"퀴즈",
			1,
			1,
			QuizType.MCQ,
			List.of(),
			List.of(),
			"1.0"
		);
		ReflectionTestUtils.setField(quiz, "id", 50L);
		QuizSubmission submission = org.mockito.Mockito.mock(
			QuizSubmission.class
		);
		Diagnosis diagnosis = Diagnosis.pending(
			session,
			submission,
			"진단 질문",
			new DiagnosisData(
				"1.0",
				List.of(),
				List.of(),
				List.of(),
				"힌트"
			)
		);
		ReflectionTestUtils.setField(diagnosis, "id", 30L);
		session.startDiagnosis(
			30L,
			UiAction.diagnosisQuestion("진단 질문", 30L)
		);
		return new Fixture(session, diagnosis);
	}

	private void assertError(
		org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
		ErrorCode errorCode
	) {
		assertThatThrownBy(action)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}

	private record Fixture(
		LearningSession session,
		Diagnosis diagnosis
	) {
	}
}
