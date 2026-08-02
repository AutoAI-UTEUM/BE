package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.exam.dto.CreateExamRequest;
import io.edupilot.exam.dto.ExamQuestionRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.QuizOption;
import io.edupilot.quiz.RubricCriterion;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class InstructorExamServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

	@Mock private ClassroomService classroomService;
	@Mock private ExamRepository examRepository;
	@Mock private ExamQuestionRepository questionRepository;
	@Mock private ExamSubmissionRepository submissionRepository;
	@Mock private ExamAnswerRepository answerRepository;

	private InstructorExamService service;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		service = new InstructorExamService(
			classroomService,
			examRepository,
			questionRepository,
			submissionRepository,
			answerRepository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		User instructor = User.create(
			"instructor@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		);
		ReflectionTestUtils.setField(instructor, "id", 1L);
		classroom = Classroom.create(
			instructor,
			"Classroom",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			"EXAMCODE"
		);
		ReflectionTestUtils.setField(classroom, "id", 10L);
	}

	@Test
	void createsEmptyDraftAndAllowsIncompleteRubricUntilPublish() {
		when(classroomService.requireOwnerForUpdate(1L, UserRole.INSTRUCTOR, 10L))
			.thenReturn(classroom);
		when(examRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			Exam exam = invocation.getArgument(0);
			ReflectionTestUtils.setField(exam, "id", 100L);
			return exam;
		});

		var empty = service.create(
			1L,
			UserRole.INSTRUCTOR,
			10L,
			new CreateExamRequest("Empty draft", null, null, null, List.of())
		);
		var incompleteRubric = service.create(
			1L,
			UserRole.INSTRUCTOR,
			10L,
			new CreateExamRequest(
				"Incomplete rubric",
				null,
				1,
				false,
				List.of(shortQuestion(new BigDecimal("0.70")))
			)
		);

		assertThat(empty.status()).isEqualTo(ExamStatus.DRAFT);
		assertThat(empty.totalScore()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(empty.questions()).isEmpty();
		assertThat(incompleteRubric.questions()).hasSize(1);
	}

	@Test
	void publishValidatesCompletenessAndIsIdempotent() {
		Exam exam = exam(false);
		ExamQuestion question = question(exam, BigDecimal.ONE);
		exam.replaceTotalScore(new BigDecimal("20"));
		when(examRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(exam));
		when(questionRepository.findByExam_IdOrderByQuestionNo(100L))
			.thenReturn(List.of(question));

		var first = service.publish(1L, UserRole.INSTRUCTOR, 100L);
		var second = service.publish(1L, UserRole.INSTRUCTOR, 100L);

		assertThat(first.status()).isEqualTo(ExamStatus.PUBLISHED);
		assertThat(first.publishedAt()).isEqualTo(NOW);
		assertThat(second.publishedAt()).isEqualTo(NOW);
	}

	@Test
	void rejectsPublishWhenRubricWeightIsIncomplete() {
		Exam exam = exam(false);
		exam.replaceTotalScore(new BigDecimal("20"));
		when(examRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(exam));
		when(questionRepository.findByExam_IdOrderByQuestionNo(100L))
			.thenReturn(List.of(question(exam, new BigDecimal("0.70"))));

		assertError(
			() -> service.publish(1L, UserRole.INSTRUCTOR, 100L),
			ErrorCode.VALIDATION_FAILED
		);
		verify(examRepository, never()).flush();
	}

	@Test
	void closeUsesTargetStateErrorAndIsIdempotent() {
		Exam draft = exam(false);
		when(examRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(draft));
		assertError(
			() -> service.close(1L, UserRole.INSTRUCTOR, 100L),
			ErrorCode.EXAM_NOT_PUBLISHED
		);

		draft.publish(NOW.minusSeconds(60));
		var first = service.close(1L, UserRole.INSTRUCTOR, 100L);
		var second = service.close(1L, UserRole.INSTRUCTOR, 100L);
		assertThat(first.status()).isEqualTo(ExamStatus.CLOSED);
		assertThat(second.closedAt()).isEqualTo(NOW);

		assertError(
			() -> service.publish(1L, UserRole.INSTRUCTOR, 100L),
			ErrorCode.EXAM_NOT_EDITABLE
		);
	}

	@Test
	void completedClassroomStillAllowsCloseAndDraftDelete() {
		Exam published = exam(false);
		published.publish(NOW.minusSeconds(60));
		classroom.complete();
		when(examRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(published));

		assertThat(service.close(1L, UserRole.INSTRUCTOR, 100L).status())
			.isEqualTo(ExamStatus.CLOSED);

		Exam draft = exam(true);
		when(examRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(draft));
		service.delete(1L, UserRole.INSTRUCTOR, 100L);
		verify(questionRepository).deleteByExam_Id(100L);
		verify(examRepository).delete(draft);
	}

	private Exam exam(boolean resetClassroom) {
		if (resetClassroom) {
			ReflectionTestUtils.setField(classroom, "status", io.edupilot.classroom.ClassroomStatus.ACTIVE);
		}
		Exam exam = Exam.create(classroom, 1, "Exam", null, false);
		ReflectionTestUtils.setField(exam, "id", 100L);
		return exam;
	}

	private ExamQuestion question(Exam exam, BigDecimal rubricWeight) {
		ExamQuestion question = ExamQuestion.create(
			exam,
			1,
			ExamQuestionType.SHORT,
			new BigDecimal("20"),
			new ExamPublicQuestion("Explain", List.of()),
			new ExamPrivateAnswer(
				"Reference",
				null,
				null,
				List.of(new RubricCriterion("Accuracy", rubricWeight))
			),
			"1.0"
		);
		ReflectionTestUtils.setField(question, "id", 200L);
		return question;
	}

	private ExamQuestionRequest shortQuestion(BigDecimal rubricWeight) {
		return new ExamQuestionRequest(
			ExamQuestionType.SHORT,
			"Explain",
			new BigDecimal("20"),
			List.<QuizOption>of(),
			null,
			null,
			"Reference",
			null,
			List.of(new RubricCriterion("Accuracy", rubricWeight))
		);
	}

	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}
}
