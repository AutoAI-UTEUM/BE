package io.edupilot.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.ai.dto.DiagnosisResponse;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.diagnosis.DiagnosisPersistenceService;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.quiz.GradingResult;
import io.edupilot.quiz.QuizPostGradingContext;
import io.edupilot.quiz.QuizSubmission;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.quiz.QuizType;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class LearningSupportPersistenceServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;

	@Mock
	private QuizSubmissionRepository submissionRepository;

	@Mock
	private QuizAssessmentRepository assessmentRepository;

	@Mock
	private LearnerMemoryCandidateRepository candidateRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private LearningMaterialRepository materialRepository;

	@Mock
	private DiagnosisRepository diagnosisRepository;

	@Test
	void completedOrDeletedSessionDiscardsAssessmentResult() {
		for (boolean deleted : List.of(false, true)) {
			LearningSession session = session();
			if (deleted) {
				session.delete();
			} else {
				session.complete();
			}
			when(sessionRepository.findOwnedForUpdate(100L, 1L))
				.thenReturn(Optional.of(session));

			var result = assessmentService().save(
				context(false),
				assessmentResponse()
			);

			assertThat(result.applied()).isFalse();
		}
		verify(assessmentRepository, never()).saveAndFlush(any());
		verify(candidateRepository, never()).save(any());
	}

	@Test
	void completedSessionDiscardsDiagnosisAndSessionPatch() {
		LearningSession session = session();
		session.complete();
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		DiagnosisPersistenceService service =
			new DiagnosisPersistenceService(
				sessionRepository,
				submissionRepository,
				diagnosisRepository
			);

		assertThat(service.savePending(
			context(false),
			diagnosisResponse()
		)).isEmpty();
		assertThat(session.getPendingDiagnosisId()).isNull();
		verify(diagnosisRepository, never()).saveAndFlush(any());
	}

	@Test
	void existingAssessmentMakesInternalReentryIdempotent() {
		LearningSession session = session();
		QuizSubmission submission = org.mockito.Mockito.mock(
			QuizSubmission.class
		);
		QuizAssessment existing = QuizAssessment.create(
			session,
			submission,
			new QuizAssessmentData(
				"1.0",
				"기존 요약",
				List.of(),
				List.of(),
				List.of(),
				"REVIEW",
				List.of(),
				List.of()
			)
		);
		ReflectionTestUtils.setField(existing, "id", 300L);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		when(assessmentRepository.findBySubmission_Id(200L))
			.thenReturn(Optional.of(existing));

		var result = assessmentService().save(
			context(false),
			assessmentResponse()
		);

		assertThat(result.applied()).isTrue();
		assertThat(result.assessmentId()).isEqualTo(300L);
		verify(assessmentRepository, never()).saveAndFlush(any());
		verify(candidateRepository, never()).save(any());
	}

	private AssessmentPersistenceService assessmentService() {
		return new AssessmentPersistenceService(
			sessionRepository,
			submissionRepository,
			assessmentRepository,
			candidateRepository,
			userRepository,
			materialRepository
		);
	}

	private LearningSession session() {
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
		return session;
	}

	private QuizPostGradingContext context(boolean passed) {
		return new QuizPostGradingContext(
			200L,
			50L,
			100L,
			1L,
			10L,
			QuizType.MCQ,
			"1.0",
			List.of(),
			List.of(),
			List.of(),
			new GradingResult(
				"1.0",
				BigDecimal.ONE,
				BigDecimal.TEN,
				List.of()
			),
			passed,
			new GradeRequest.PageContext(1, 1, "문맥"),
			List.of(io.edupilot.session.UiAction.moveNextPage())
		);
	}

	private QuizAssessmentResponse assessmentResponse() {
		return new QuizAssessmentResponse(
			"1.0",
			"요약",
			List.of(),
			List.of(),
			List.of(),
			"REVIEW",
			List.of(),
			List.of(),
			null
		);
	}

	private DiagnosisResponse diagnosisResponse() {
		return new DiagnosisResponse(
			"1.0",
			List.of(),
			List.of(),
			"질문",
			List.of(),
			"힌트",
			null
		);
	}
}
