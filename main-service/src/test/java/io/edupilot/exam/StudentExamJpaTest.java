package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.QuizOption;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:student-exam;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/student-exam"
	}
)
@ActiveProfiles("jpa-context")
@Transactional
class StudentExamJpaTest {

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamQuestionRepository questionRepository;
	@Autowired private ExamSubmissionRepository submissionRepository;
	@Autowired private StudentExamService studentExamService;
	@Autowired private ExamSubmissionPersistenceService persistenceService;
	@Autowired private ExamAiGradingService aiGradingService;
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	@MockitoBean private AiClient aiClient;

	private User learner;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		User instructor = userRepository.save(User.create(
			"student-exam-instructor@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		));
		learner = userRepository.save(User.create(
			"student-exam-learner@example.com", "hash", "Learner", UserRole.LEARNER
		));
		classroom = classroomRepository.save(Classroom.create(
			instructor,
			"Student exam classroom",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			"STUDENTEXAM"
		));
		memberRepository.save(ClassroomMember.create(
			classroom, learner, Instant.parse("2026-08-03T00:00:00Z")
		));
	}

	@Test
	void gradesDeterministicAnswersKeepsPrivateDataHiddenAndIsIdempotent() throws Exception {
		Exam exam = publishedExam(false);
		questionRepository.saveAll(List.of(
			mcq(exam, 1, "a"),
			ox(exam, 2, "false"),
			shortQuestion(exam, 3)
		));

		var request = new SubmitExamRequest(
			"request-1",
			List.of(
				new ExamAnswerRequest("q1", "a"),
				new ExamAnswerRequest("q2", "true")
			)
		);
		var first = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, exam.getId(), request
		);
		var retry = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, exam.getId(), request
		);

		assertThat(first.status()).isEqualTo(SubmissionStatus.GRADED);
		assertThat(first.score()).isEqualByComparingTo("10.00");
		assertThat(first.maxScore()).isEqualByComparingTo("30.00");
		assertThat(first.normalizedScore()).isEqualByComparingTo("33.33");
		assertThat(first.items()).extracting(item -> item.verdict())
			.containsExactly(Verdict.CORRECT, Verdict.WRONG, Verdict.WRONG);
		assertThat(first.items().get(2).answer()).isNull();
		assertThat(retry.submissionId()).isEqualTo(first.submissionId());
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isEqualTo(1);

		String studentJson = objectMapper.writeValueAsString(studentExamService.detail(
			learner.getId(), UserRole.LEARNER, exam.getId()
		));
		assertThat(studentJson)
			.contains("\"optionId\":\"a\"")
			.doesNotContain("choiceId")
			.doesNotContain("correctAnswer")
			.doesNotContain("answerChoiceId")
			.doesNotContain("answerValue")
			.doesNotContain("referenceAnswer")
			.doesNotContain("explanation")
			.doesNotContain("modelAnswer")
			.doesNotContain("rubric");

		assertError(
			() -> studentExamService.submit(
				learner.getId(), UserRole.LEARNER, exam.getId(),
				new SubmitExamRequest("request-2", List.of())
			),
			ErrorCode.EXAM_ALREADY_SUBMITTED
		);
	}

	@Test
	void hidesDraftAndRejectsUnknownDuplicateAndTypeMismatchedAnswers() {
		Exam draft = examRepository.save(Exam.create(
			classroom, null, "Draft", null, false
		));
		assertError(
			() -> studentExamService.detail(
				learner.getId(), UserRole.LEARNER, draft.getId()
			),
			ErrorCode.EXAM_NOT_FOUND
		);

		Exam exam = publishedExam(true);
		questionRepository.saveAll(List.of(mcq(exam, 1, "a"), ox(exam, 2, "true")));
		assertInvalid(exam, List.of(new ExamAnswerRequest("q99", "a")));
		assertInvalid(exam, List.of(
			new ExamAnswerRequest("q1", "a"),
			new ExamAnswerRequest("q1", "b")
		));
		assertInvalid(exam, List.of(new ExamAnswerRequest("q1", "unknown")));
		assertInvalid(exam, List.of(new ExamAnswerRequest("q2", "yes")));
	}

	@Test
	void preservesAttemptsAndReturnsLatestWhenRetakeIsAllowed() {
		Exam exam = Exam.create(classroom, 1, "Retake", null, true);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		exam.publish(Instant.parse("2026-08-03T00:00:00Z"));
		exam = examRepository.saveAndFlush(exam);
		questionRepository.saveAndFlush(mcq(exam, 1, "a"));

		var first = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest(
				"retake-1", List.of(new ExamAnswerRequest("q1", "b"))
			)
		);
		var second = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest(
				"retake-2", List.of(new ExamAnswerRequest("q1", "a"))
			)
		);
		var latest = studentExamService.mySubmission(
			learner.getId(), UserRole.LEARNER, exam.getId(), null
		);

		assertThat(first.attemptNo()).isEqualTo(1);
		assertThat(second.attemptNo()).isEqualTo(2);
		assertThat(latest.submissionId()).isEqualTo(second.submissionId());
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isEqualTo(2);
		assertThat(submissionRepository.countDistinctUsersByExamId(exam.getId()))
			.isEqualTo(1);
	}

	@Test
	void gradesAnsweredShortAndEssayByTypeWithDefaultRubric() {
		Exam exam = Exam.create(classroom, 1, "Subjective", null, false);
		exam.replaceTotalScore(new BigDecimal("20.00"));
		exam.publish(Instant.parse("2026-08-03T00:00:00Z"));
		exam = examRepository.saveAndFlush(exam);
		Long examId = exam.getId();
		questionRepository.saveAllAndFlush(List.of(
			shortQuestion(exam, 1),
			ExamQuestion.create(
				exam,
				2,
				ExamQuestionType.ESSAY,
				new BigDecimal("10.00"),
				new ExamPublicQuestion("ESSAY", List.of()),
				new ExamPrivateAnswer(null, null, null, null, "Model", List.of()),
				"1.0"
			)
		));
		when(aiClient.grade(any())).thenAnswer(invocation -> {
			GradeRequest request = invocation.getArgument(0);
			String questionId = request.items().get(0).questionId();
			BigDecimal score = request.quizType().equals("SHORT")
				? new BigDecimal("7.00") : new BigDecimal("8.00");
			assertThat(request.quizId()).isEqualTo(examId);
			assertThat(request.pageContext()).isNull();
			assertThat(request.learnerMemoryDigest()).isNull();
			assertThat(request.items().get(0).rubric()).singleElement()
				.satisfies(rubric -> {
					assertThat(rubric.criterion()).isEqualTo("모범 답안 부합도");
					assertThat(rubric.weight()).isEqualByComparingTo(BigDecimal.ONE);
				});
			return new GradeResponse(
				"1.0", examId, request.quizType(), score,
				new BigDecimal("10.00"),
				List.of(new GradeResponse.Item(
					questionId, score, new BigDecimal("10.00"), "PARTIAL", "Feedback"
				)),
				null
			);
		});

		var submitted = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, examId,
			new SubmitExamRequest("subjective-1", List.of(
				new ExamAnswerRequest("q1", "Short answer"),
				new ExamAnswerRequest("q2", "Essay answer")
			))
		);
		assertThat(submitted.status()).isEqualTo(SubmissionStatus.SUBMITTED);
		assertThat(submitted.items()).allSatisfy(item -> {
			assertThat(item.score()).isNull();
			assertThat(item.verdict()).isNull();
			assertThat(item.feedback()).isNull();
		});
		var result = grade(submitted.submissionId());

		assertThat(result.status()).isEqualTo(SubmissionStatus.GRADED);
		assertThat(result.score()).isEqualByComparingTo("15.00");
		assertThat(result.normalizedScore()).isEqualByComparingTo("75.00");
		assertThat(result.items()).extracting(item -> item.score())
			.containsExactly(new BigDecimal("7.00"), new BigDecimal("8.00"));
	}

	@Test
	void preservesSuccessfulGroupAndNullableFailedGroup() {
		Exam exam = Exam.create(classroom, 1, "Partial failure", null, false);
		exam.replaceTotalScore(new BigDecimal("20.00"));
		exam.publish(Instant.parse("2026-08-03T00:00:00Z"));
		exam = examRepository.saveAndFlush(exam);
		Long examId = exam.getId();
		questionRepository.saveAllAndFlush(List.of(
			shortQuestion(exam, 1),
			ExamQuestion.create(
				exam, 2, ExamQuestionType.ESSAY, new BigDecimal("10.00"),
				new ExamPublicQuestion("ESSAY", List.of()),
				new ExamPrivateAnswer(null, null, null, null, "Model", List.of()), "1.0"
			)
		));
		when(aiClient.grade(any()))
			.thenReturn(new GradeResponse(
				"1.0", examId, "SHORT", new BigDecimal("7.00"),
				new BigDecimal("10.00"),
				List.of(new GradeResponse.Item(
					"q1", new BigDecimal("7.00"), new BigDecimal("10.00"),
					"PARTIAL", "Feedback"
				)), null
			))
			.thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));

		var submitted = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, examId,
			new SubmitExamRequest("partial-failure", List.of(
				new ExamAnswerRequest("q1", "Short answer"),
				new ExamAnswerRequest("q2", "Essay answer")
			))
		);
		assertThat(submitted.status()).isEqualTo(SubmissionStatus.SUBMITTED);
		var result = grade(submitted.submissionId());

		assertThat(result.status()).isEqualTo(SubmissionStatus.GRADING_FAILED);
		assertThat(result.score()).isNull();
		assertThat(result.normalizedScore()).isNull();
		assertThat(result.gradedAt()).isNull();
		assertThat(result.items().get(0).score()).isEqualByComparingTo("7.00");
		assertThat(result.items().get(1).score()).isNull();
		assertThat(result.items().get(1).verdict()).isNull();
		assertThat(result.items().get(1).feedback()).isNull();
	}

	@Test
	void gradingFailureDoesNotConsumeNonRetakeAttempt() {
		Exam exam = Exam.create(classroom, 1, "Retry failure", null, false);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		exam.publish(Instant.parse("2026-08-03T00:00:00Z"));
		exam = examRepository.saveAndFlush(exam);
		questionRepository.saveAndFlush(shortQuestion(exam, 1));
		when(aiClient.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));

		var first = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest(
				"failed-attempt-1", List.of(new ExamAnswerRequest("q1", "Answer"))
			)
		);
		var failed = grade(first.submissionId());
		var retry = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest(
				"failed-attempt-2", List.of(new ExamAnswerRequest("q1", "Answer"))
			)
		);

		assertThat(failed.status()).isEqualTo(SubmissionStatus.GRADING_FAILED);
		assertThat(retry.status()).isEqualTo(SubmissionStatus.SUBMITTED);
		assertThat(retry.attemptNo()).isEqualTo(2);
		assertThat(studentExamService.detail(
			learner.getId(), UserRole.LEARNER, exam.getId()
		).submittable()).isFalse();
	}

	@Test
	void lateWorkerCannotApplyAfterLeaseWasReclaimed() {
		Exam exam = Exam.create(classroom, 1, "Lease", null, false);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		exam.publish(Instant.parse("2026-08-03T00:00:00Z"));
		exam = examRepository.saveAndFlush(exam);
		questionRepository.saveAndFlush(shortQuestion(exam, 1));
		var submitted = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest(
				"lease-1", List.of(new ExamAnswerRequest("q1", "Answer"))
			)
		);
		Instant firstClaim = Instant.parse("2026-08-03T01:00:00Z");
		String firstToken = "00000000-0000-0000-0000-000000000001";
		String secondToken = "00000000-0000-0000-0000-000000000002";

		assertThat(persistenceService.claimGradingLease(
			submitted.submissionId(), firstToken, firstClaim, firstClaim.plusSeconds(300)
		)).isTrue();
		assertThat(persistenceService.claimGradingLease(
			submitted.submissionId(), secondToken,
			firstClaim.plusSeconds(301), firstClaim.plusSeconds(601)
		)).isTrue();
		assertThat(persistenceService.applyAiGrading(
			submitted.submissionId(), firstToken,
			new ExamAiGradingOutcome(java.util.Map.of(), true)
		)).isFalse();
		assertThat(persistenceService.applyAiGrading(
			submitted.submissionId(), secondToken,
			new ExamAiGradingOutcome(java.util.Map.of(), true)
		)).isTrue();
	}

	private io.edupilot.exam.dto.ExamSubmissionResponse grade(Long submissionId) {
		Instant now = Instant.parse("2026-08-03T01:00:00Z");
		String token = "00000000-0000-0000-0000-000000000001";
		assertThat(persistenceService.claimGradingLease(
			submissionId, token, now, now.plusSeconds(300)
		)).isTrue();
		assertThat(persistenceService.applyAiGrading(
			submissionId, token, aiGradingService.grade(submissionId)
		)).isTrue();
		return studentExamService.mySubmission(
			learner.getId(), UserRole.LEARNER,
			submissionRepository.findById(submissionId).orElseThrow().getExamId(),
			null
		);
	}

	private Exam publishedExam(boolean allowRetake) {
		Exam exam = Exam.create(classroom, 1, "Published", null, allowRetake);
		exam.replaceTotalScore(new BigDecimal("30.00"));
		exam.publish(Instant.parse("2026-08-03T00:00:00Z"));
		return examRepository.saveAndFlush(exam);
	}

	private ExamQuestion mcq(Exam exam, int number, String answer) {
		return ExamQuestion.create(
			exam,
			number,
			ExamQuestionType.MCQ,
			new BigDecimal("10.00"),
			new ExamPublicQuestion("MCQ", List.of(
				new QuizOption("a", "A"), new QuizOption("b", "B")
			)),
			new ExamPrivateAnswer(answer, null, "Explanation", null, null, List.of()),
			"1.0"
		);
	}

	private ExamQuestion ox(Exam exam, int number, String answer) {
		return ExamQuestion.create(
			exam,
			number,
			ExamQuestionType.OX,
			new BigDecimal("10.00"),
			new ExamPublicQuestion("OX", List.of()),
			new ExamPrivateAnswer(null, Boolean.valueOf(answer), "Explanation", null, null, List.of()),
			"1.0"
		);
	}

	private ExamQuestion shortQuestion(Exam exam, int number) {
		return ExamQuestion.create(
			exam,
			number,
			ExamQuestionType.SHORT,
			new BigDecimal("10.00"),
			new ExamPublicQuestion("SHORT", List.of()),
			new ExamPrivateAnswer(null, null, null, "Reference", null, List.of()),
			"1.0"
		);
	}

	private void assertInvalid(Exam exam, List<ExamAnswerRequest> answers) {
		assertError(
			() -> studentExamService.submit(
				learner.getId(), UserRole.LEARNER, exam.getId(),
				new SubmitExamRequest("invalid-" + System.nanoTime(), answers)
			),
			ErrorCode.INVALID_EXAM_ANSWER
		);
	}

	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}
}
