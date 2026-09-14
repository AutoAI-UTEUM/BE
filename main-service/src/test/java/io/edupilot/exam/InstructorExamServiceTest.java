package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.exam.dto.CreateExamRequest;
import io.edupilot.exam.dto.ExamQuestionRequest;
import io.edupilot.exam.dto.ManualScoreAdjustmentRequest;
import io.edupilot.exam.dto.UpdateExamRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.notification.ExamNotificationDispatcher;
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
	@Mock private ExamSubmissionPersistenceService submissionPersistenceService;
	@Mock private ExamNotificationDispatcher notificationDispatcher;

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
			submissionPersistenceService,
			new ExamSubmissionScoreCalculator(),
			notificationDispatcher,
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
			new CreateExamRequest(
				"Empty draft", null, null, null, null, List.of()
			)
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
				null,
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
		verify(notificationDispatcher, times(1)).publishedAfterCommit(
			100L, 10L, "Exam"
		);
	}

	@Test
	void dueAtSupportsFutureCreateExplicitClearAndUntouchedPastValue() {
		Instant future = NOW.plusSeconds(3_600);
		when(classroomService.requireOwnerForUpdate(1L, UserRole.INSTRUCTOR, 10L))
			.thenReturn(classroom);
		when(examRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			Exam exam = invocation.getArgument(0);
			ReflectionTestUtils.setField(exam, "id", 100L);
			return exam;
		});

		var created = service.create(
			1L,
			UserRole.INSTRUCTOR,
			10L,
			new CreateExamRequest(
				"Due exam", null, null, false, future, List.of()
			)
		);
		assertThat(created.dueAt()).isEqualTo(future);

		Exam existing = Exam.create(
			classroom, 1, "Exam", null, false, NOW.minusSeconds(60)
		);
		ReflectionTestUtils.setField(existing, "id", 100L);
		when(examRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(existing));
		UpdateExamRequest unrelated = new UpdateExamRequest();
		unrelated.setDescription("unchanged due date");
		assertThat(service.update(
			1L, UserRole.INSTRUCTOR, 100L, unrelated
		).dueAt()).isEqualTo(NOW.minusSeconds(60));

		UpdateExamRequest clear = new UpdateExamRequest();
		clear.setDueAt(null);
		assertThat(service.update(
			1L, UserRole.INSTRUCTOR, 100L, clear
		).dueAt()).isNull();

		Instant rescheduled = NOW.plusSeconds(7_200);
		UpdateExamRequest reschedule = new UpdateExamRequest();
		reschedule.setDueAt(rescheduled);
		assertThat(service.update(
			1L, UserRole.INSTRUCTOR, 100L, reschedule
		).dueAt()).isEqualTo(rescheduled);
	}

	@Test
	void rejectsPresentDueAtUnlessItIsStrictlyFuture() {
		when(classroomService.requireOwnerForUpdate(1L, UserRole.INSTRUCTOR, 10L))
			.thenReturn(classroom);
		assertError(
			() -> service.create(
				1L,
				UserRole.INSTRUCTOR,
				10L,
				new CreateExamRequest(
					"Past", null, null, false, NOW, List.of()
				)
			),
			ErrorCode.INVALID_EXAM_DUE_AT
		);

		Exam exam = exam(false);
		when(examRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(exam));
		UpdateExamRequest update = new UpdateExamRequest();
		update.setDueAt(NOW.minusSeconds(1));
		assertError(
			() -> service.update(1L, UserRole.INSTRUCTOR, 100L, update),
			ErrorCode.INVALID_EXAM_DUE_AT
		);
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

	@Test
	void regradeRequiresOwnedExamAndDelegatesFailedSubmissionRecovery() {
		Exam exam = exam(false);
		when(examRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(exam));

		service.regrade(1L, UserRole.INSTRUCTOR, 100L, 300L);

		verify(submissionPersistenceService).regradeFailedSubmission(100L, 300L);
		assertError(
			() -> service.regrade(2L, UserRole.INSTRUCTOR, 100L, 300L),
			ErrorCode.CLASSROOM_NOT_FOUND
		);
	}

	@Test
	void adjustsOnlyManualScoreAndRecalculatesEffectiveSubmissionScoresUnderLock() {
		AdjustmentFixture fixture = adjustableSubmission(SubmissionStatus.GRADED);

		var response = service.adjustAnswerScore(
			1L,
			UserRole.INSTRUCTOR,
			100L,
			300L,
			"q1",
			new ManualScoreAdjustmentRequest(new BigDecimal("8.00"))
		);

		assertThat(fixture.answer().getScore()).isEqualByComparingTo("5.00");
		assertThat(fixture.answer().getManualScore()).isEqualByComparingTo("8.00");
		assertThat(fixture.answer().effectiveScore()).isEqualByComparingTo("8.00");
		assertThat(fixture.answer().getAdjustedBy()).isEqualTo(1L);
		assertThat(fixture.answer().getAdjustedAt()).isEqualTo(NOW);
		assertThat(fixture.answer().getVerdict()).isEqualTo(Verdict.PARTIAL);
		assertThat(fixture.submission().getScore()).isEqualByComparingTo("8.00");
		assertThat(fixture.submission().getNormalizedScore()).isEqualByComparingTo("40.00");
		assertThat(response.score()).isEqualByComparingTo("8.00");
		assertThat(response.items().getFirst().score()).isEqualByComparingTo("8.00");
		assertThat(response.items().getFirst().manualScore()).isEqualByComparingTo("8.00");
		assertThat(response.items().getFirst().adjustedAt()).isEqualTo(NOW);
		verify(submissionRepository).findByIdAndExamIdForUpdate(300L, 100L);
		verifyNoInteractions(notificationDispatcher);
	}

	@Test
	void recalculatesVerdictAtZeroMaximumAndPartialBoundaries() {
		AdjustmentFixture fixture = adjustableSubmission(SubmissionStatus.GRADED);

		service.adjustAnswerScore(
			1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
			new ManualScoreAdjustmentRequest(BigDecimal.ZERO)
		);
		assertThat(fixture.answer().getVerdict()).isEqualTo(Verdict.WRONG);

		service.adjustAnswerScore(
			1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
			new ManualScoreAdjustmentRequest(new BigDecimal("20.00"))
		);
		assertThat(fixture.answer().getVerdict()).isEqualTo(Verdict.CORRECT);

		service.adjustAnswerScore(
			1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
			new ManualScoreAdjustmentRequest(new BigDecimal("7.50"))
		);
		assertThat(fixture.answer().getVerdict()).isEqualTo(Verdict.PARTIAL);
	}

	@Test
	void rejectsOutOfRangeMissingQuestionAndNonGradedSubmissions() {
		adjustableSubmission(SubmissionStatus.GRADED);
		assertError(
			() -> service.adjustAnswerScore(
				1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
				new ManualScoreAdjustmentRequest(new BigDecimal("-0.01"))
			),
			ErrorCode.SCORE_OUT_OF_RANGE
		);
		assertError(
			() -> service.adjustAnswerScore(
				1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
				new ManualScoreAdjustmentRequest(new BigDecimal("20.01"))
			),
			ErrorCode.SCORE_OUT_OF_RANGE
		);
		assertError(
			() -> service.adjustAnswerScore(
				1L, UserRole.INSTRUCTOR, 100L, 300L, "q999",
				new ManualScoreAdjustmentRequest(BigDecimal.ONE)
			),
			ErrorCode.EXAM_NOT_FOUND
		);

		adjustableSubmission(SubmissionStatus.SUBMITTED);
		assertError(
			() -> service.adjustAnswerScore(
				1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
				new ManualScoreAdjustmentRequest(BigDecimal.ONE)
			),
			ErrorCode.SUBMISSION_NOT_ADJUSTABLE
		);
		adjustableSubmission(SubmissionStatus.GRADING_FAILED);
		assertError(
			() -> service.adjustAnswerScore(
				1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
				new ManualScoreAdjustmentRequest(BigDecimal.ONE)
			),
			ErrorCode.SUBMISSION_NOT_ADJUSTABLE
		);
	}

	@Test
	void rejectsLearnerOtherInstructorAndForeignSubmission() {
		assertError(
			() -> service.adjustAnswerScore(
				2L, UserRole.LEARNER, 100L, 300L, "q1",
				new ManualScoreAdjustmentRequest(BigDecimal.ONE)
			),
			ErrorCode.ACCESS_DENIED
		);

		Exam exam = exam(false);
		when(examRepository.findWithClassroomById(100L)).thenReturn(Optional.of(exam));
		assertError(
			() -> service.adjustAnswerScore(
				2L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
				new ManualScoreAdjustmentRequest(BigDecimal.ONE)
			),
			ErrorCode.CLASSROOM_NOT_FOUND
		);

		when(submissionRepository.findByIdAndExamIdForUpdate(300L, 100L))
			.thenReturn(Optional.empty());
		assertError(
			() -> service.adjustAnswerScore(
				1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
				new ManualScoreAdjustmentRequest(BigDecimal.ONE)
			),
			ErrorCode.EXAM_NOT_FOUND
		);
	}

	@Test
	void readjustmentAuditUsesPreviousEffectiveScoreWithoutAnswerContent() {
		AdjustmentFixture fixture = adjustableSubmission(SubmissionStatus.GRADED);
		Logger logger = (Logger) LoggerFactory.getLogger(InstructorExamService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			service.adjustAnswerScore(
				1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
				new ManualScoreAdjustmentRequest(new BigDecimal("5.00"))
			);
			service.adjustAnswerScore(
				1L, UserRole.INSTRUCTOR, 100L, 300L, "q1",
				new ManualScoreAdjustmentRequest(new BigDecimal("8.00"))
			);

			ILoggingEvent event = appender.list.getLast();
			Map<String, Object> fields = event.getKeyValuePairs().stream()
				.collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
			assertThat(fields)
				.containsEntry("action", "MANUAL_SCORE_ADJUSTED")
				.containsEntry("actorUserId", 1L)
				.containsEntry("examId", 100L)
				.containsEntry("submissionId", 300L)
				.containsEntry("questionId", "q1")
				.containsEntry("beforeScore", new BigDecimal("5.00"))
				.containsEntry("afterScore", new BigDecimal("8.00"))
				.containsEntry("adjustedAt", NOW);
			assertThat(event.getFormattedMessage())
				.doesNotContain(fixture.answer().getAnswer());
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
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
				null,
				null,
				null,
				"Reference",
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
			List.of(),
			null,
			null,
			null,
			"Reference",
			null,
			List.of(new RubricCriterion("Accuracy", rubricWeight))
		);
	}

	private AdjustmentFixture adjustableSubmission(SubmissionStatus status) {
		Exam exam = exam(false);
		ExamQuestion question = question(exam, BigDecimal.ONE);
		User learner = User.create(
			"learner@example.com", "hash", "Learner", UserRole.LEARNER
		);
		ReflectionTestUtils.setField(learner, "id", 2L);
		ExamSubmission submission = ExamSubmission.create(
			exam,
			learner,
			1,
			"request-id",
			new BigDecimal("20.00"),
			NOW.minusSeconds(60)
		);
		ReflectionTestUtils.setField(submission, "id", 300L);
		ExamAnswer answer = ExamAnswer.create(
			submission, question, "confidential student answer", new BigDecimal("20.00")
		);
		ReflectionTestUtils.setField(answer, "id", 400L);
		answer.recordGrade(new BigDecimal("5.00"), Verdict.PARTIAL, "feedback");
		if (status == SubmissionStatus.GRADED) {
			submission.complete(
				new BigDecimal("5.00"), new BigDecimal("25.00"), NOW.minusSeconds(30)
			);
		} else if (status == SubmissionStatus.GRADING_FAILED) {
			submission.failGrading();
		}
		when(examRepository.findWithClassroomById(100L)).thenReturn(Optional.of(exam));
		when(submissionRepository.findByIdAndExamIdForUpdate(300L, 100L))
			.thenReturn(Optional.of(submission));
		when(answerRepository.findBySubmission_IdOrderByQuestion_Id(300L))
			.thenReturn(List.of(answer));
		return new AdjustmentFixture(submission, answer);
	}

	private record AdjustmentFixture(ExamSubmission submission, ExamAnswer answer) {
	}

	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}
}
