package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.ManualScoreAdjustmentRequest;
import io.edupilot.exam.dto.SaveExamAttemptDraftRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.quiz.QuizOption;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
	"spring.datasource.url=jdbc:h2:mem:exam-failure-security;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/exam-failure-security"
})
@ActiveProfiles("jpa-context")
class ExamFailureSecurityJpaTest {
	@DynamicPropertySource
	static void isolatedMysql(DynamicPropertyRegistry registry) {
		if (!"true".equals(System.getenv("RUNTIME_REGRESSIONS_MYSQL"))) { return; }
		registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:33316/runtime_exam_failure_synthetic");
		registry.add("spring.datasource.username", () -> "root");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}
	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamQuestionRepository questionRepository;
	@Autowired private ExamSubmissionRepository submissionRepository;
	@Autowired private ExamAnswerRepository answerRepository;
	@Autowired private ExamAttemptStartRepository startRepository;
	@Autowired private ExamAttemptDraftRepository draftRepository;
	@Autowired private ExamAttemptDraftService draftService;
	@Autowired private StudentExamService studentService;
	@Autowired private InstructorExamService instructorService;
	@Autowired private ExamSubmissionPersistenceService persistenceService;
	@Autowired private ExamGradingWorker worker;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private WebApplicationContext webContext;
	@Autowired private JwtTokenProvider tokenProvider;
	@Autowired private TraceIdFilter traceIdFilter;
	@MockitoBean private AiClient aiClient;
	@MockitoBean private ExamGradingDispatcher dispatcher;
	@MockitoBean private ExamGradingRecoveryScheduler recoveryScheduler;

	private User learner;
	private User instructor;
	private Exam exam;

	@BeforeEach
	void setUp() {
		if ("true".equals(System.getenv("RUNTIME_REGRESSIONS_MYSQL"))) {
			assertThat(jdbcTemplate.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbcTemplate.queryForObject("select database()", String.class)).isEqualTo("runtime_exam_failure_synthetic");
		}
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		instructor = userRepository.saveAndFlush(io.edupilot.VerifiedTestUsers.verified(User.create(
			"security-instructor-" + suffix + "@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		)));
		learner = userRepository.saveAndFlush(io.edupilot.VerifiedTestUsers.verified(User.create(
			"security-learner-" + suffix + "@example.com", "hash", "Learner", UserRole.LEARNER
		)));
		Classroom classroom = classroomRepository.saveAndFlush(Classroom.create(
			instructor, "Security exam", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE, null, "SEC" + suffix
		));
		memberRepository.saveAndFlush(ClassroomMember.create(classroom, learner, Instant.now()));
		exam = Exam.create(classroom, 1, "Mixed exam", null, false);
		exam.replaceTotalScore(new BigDecimal("30.00"));
		exam.publish(Instant.now());
		exam = examRepository.saveAndFlush(exam);
		questionRepository.saveAllAndFlush(List.of(
			ExamQuestion.create(exam, 1, ExamQuestionType.MCQ, BigDecimal.TEN,
				new ExamPublicQuestion("MCQ", List.of(new QuizOption("a", "A"), new QuizOption("b", "B"))),
				new ExamPrivateAnswer("a", null, "Explanation", null, null, List.of()), "1.0"),
			ExamQuestion.create(exam, 2, ExamQuestionType.OX, BigDecimal.TEN,
				new ExamPublicQuestion("OX", List.of()),
				new ExamPrivateAnswer(null, true, "Explanation", null, null, List.of()), "1.0"),
			ExamQuestion.create(exam, 3, ExamQuestionType.SHORT, BigDecimal.TEN,
				new ExamPublicQuestion("SHORT", List.of()),
				new ExamPrivateAnswer(null, null, null, "Reference", null, List.of()), "1.0")
		));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\r\n", "\u2003", "\u2003\u3000", "\u00a0", "\u202f"})
	void rejectsExplicitBlankBeforeStoringOrDispatching(String answer) {
		assertError(() -> submit("invalid-blank", answer), ErrorCode.INVALID_EXAM_ANSWER);
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isZero();
		verify(dispatcher, never()).dispatchAfterCommit(any(), any());
		verify(aiClient, never()).grade(any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " \t\r\n", "\u2003", "\u00a0", "\u0000"})
	void rejectsInvalidPersistedAnswerInsteadOfSilentlyDroppingItFromGrading(String legacyAnswer) {
		var submitted = submit("legacy-blank", "Answer");
		jdbcTemplate.update("update exam_answers set answer = ? where submission_id = ? and answer = ?",
			legacyAnswer, submitted.submissionId(), "Answer");

		assertError(() -> persistenceService.prepareAiGrading(submitted.submissionId()),
			ErrorCode.GRADING_RESULT_INVALID);
		worker.grade(submitted.submissionId());
		assertThat(submissionRepository.findById(submitted.submissionId()).orElseThrow().getStatus())
			.isEqualTo(SubmissionStatus.GRADING_FAILED);
		assertThat(answerRepository.findBySubmission_IdOrderByQuestion_Id(submitted.submissionId()))
			.filteredOn(answer -> answer.getQuestionType() == ExamQuestionType.SHORT)
			.singleElement().satisfies(answer -> {
				assertThat(answer.getScore()).isNull();
				assertThat(answer.getVerdict()).isNull();
			});
		verify(aiClient, never()).grade(any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"자료의 퍼짐 정도를 나타냅니다.", "Measures the spread of values.",
		"\u2003한글 답안\u2003"})
	void normalWrittenAnswersKeepTheirContentAndAreGraded(String answer) {
		doAnswer(invocation -> {
			GradeRequest request = invocation.getArgument(0);
			assertThat(request.studentAnswers()).singleElement()
				.satisfies(item -> assertThat(item.answer()).isEqualTo(answer));
			return successfulGrade();
		}).when(aiClient).grade(any());
		var submitted = submit("normal-text", answer);
		worker.grade(submitted.submissionId());
		var graded = studentService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), null);
		assertThat(graded.status()).isEqualTo(SubmissionStatus.GRADED);
		assertThat(graded.score()).isEqualByComparingTo("30.00");
		assertThat(graded.items().get(2).answer()).isEqualTo(answer);
	}

	@Test
	void omittedQuestionsStillReceiveZeroWithoutAi() {
		var partial = studentService.submit(learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest("omitted", List.of(new ExamAnswerRequest("q1", "a"))));
		assertThat(partial.status()).isEqualTo(SubmissionStatus.GRADED);
		assertThat(partial.score()).isEqualByComparingTo("10.00");
		assertThat(partial.items().subList(1, 3)).allSatisfy(item -> {
			assertThat(item.answer()).isNull();
			assertThat(item.score()).isEqualByComparingTo("0.00");
			assertThat(item.verdict()).isEqualTo(Verdict.WRONG);
		});
		verify(dispatcher, never()).dispatchAfterCommit(any(), any());
		verify(aiClient, never()).grade(any());
	}

	@Test
	void allOmittedSubmissionIsGradedZero() {
		var result = studentService.submit(learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest("all-omitted", List.of()));
		assertThat(result.status()).isEqualTo(SubmissionStatus.GRADED);
		assertThat(result.score()).isEqualByComparingTo("0.00");
		verify(aiClient, never()).grade(any());
	}

	@Test
	void hidesFailedPartialResultsInGetAndIdenticalPost() {
		when(aiClient.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));
		var submitted = submit("hidden-failure", "Answer");
		worker.grade(submitted.submissionId());

		var get = studentService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), 1);
		var post = submit("hidden-failure", "Changed answer");
		assertThat(get.status()).isEqualTo(SubmissionStatus.GRADING_FAILED);
		assertThat(post.submissionId()).isEqualTo(submitted.submissionId());
		assertThat(get.items()).allSatisfy(item -> {
			assertThat(item.score()).isNull();
			assertThat(item.verdict()).isNull();
			assertThat(item.feedback()).isNull();
		});
		assertThat(post.items()).allSatisfy(item -> {
			assertThat(item.score()).isNull();
			assertThat(item.verdict()).isNull();
			assertThat(item.feedback()).isNull();
		});
	}

	@Test
	void failedNonRetakeSubmissionCannotBeReplacedWithChangedAnswers() {
		when(aiClient.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));
		var submitted = submit("fixed-answer", "Answer");
		worker.grade(submitted.submissionId());

		assertError(() -> submit("changed-attempt", "Changed answer"), ErrorCode.EXAM_ALREADY_SUBMITTED);
		assertThat(studentService.detail(learner.getId(), UserRole.LEARNER, exam.getId()).submittable())
			.isFalse();
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isEqualTo(1);
	}

	@Test
	void failedGetAndRepeatedPostSerializeMaskedResultsAndBlankInputReturns400() throws Exception {
		var mvc = MockMvcBuilders.webAppContextSetup(webContext)
			.apply(springSecurity()).addFilters(traceIdFilter).build();
		String bearer = "Bearer " + tokenProvider.createAccessToken(learner);
		String submissions = "/api/exams/" + exam.getId() + "/submissions";
		mvc.perform(post(submissions).header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"requestId\":\"http-blank\",\"answers\":[{\"questionId\":\"q3\",\"answer\":\"\u2003\"}]}"))
			.andExpect(status().isBadRequest());
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isZero();
		when(aiClient.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));
		var failed = submit("http-failure", "Original answer");
		worker.grade(failed.submissionId());
		var get = mvc.perform(get(submissions + "/me?attemptNo=1")
			.header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()).andReturn();
		var post = mvc.perform(post(submissions).header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"requestId\":\"http-failure\",\"answers\":[{\"questionId\":\"q3\",\"answer\":\"Changed\"}]}"))
			.andExpect(status().isOk()).andReturn();
		ObjectMapper mapper = new ObjectMapper();
		for (var response : List.of(get, post)) {
			JsonNode data = mapper.readTree(response.getResponse().getContentAsByteArray()).get("data");
			assertThat(data.get("status").textValue()).isEqualTo("GRADING_FAILED");
			for (String field : List.of("score", "normalizedScore", "gradedAt")) {
				assertThat(data.get(field).isNull()).as(field).isTrue();
			}
			for (JsonNode item : data.get("items")) {
				for (String field : List.of("score", "verdict", "feedback")) {
					assertThat(item.get(field).isNull()).as(field).isTrue();
				}
				for (String field : List.of("correctAnswer", "explanation", "answerChoiceId", "answerValue",
					"referenceAnswer", "modelAnswer", "rubric", "privateAnswer", "isCorrect", "manualScore")) {
					assertThat(item.has(field)).as(field).isFalse();
				}
			}
		}
	}

	@ParameterizedTest
	@EnumSource(value = ErrorCode.class, names = {"AI_SERVICE_TIMEOUT", "AI_RESPONSE_INVALID"})
	void actualAiFailureRecoversUsingFixedAnswersAndSameAttempt(ErrorCode failureCode) {
		when(aiClient.grade(any())).thenThrow(new AiClientException(failureCode));
		var original = submit("recover-original", "Original answer");
		worker.grade(original.submissionId());
		assertFailedResultsHidden(original.submissionId());
		assertThat(submissionRepository.findById(original.submissionId()).orElseThrow().getScore())
			.isNull();

		var pending = studentService.regradeMySubmission(learner.getId(), UserRole.LEARNER, exam.getId());
		var repeated = studentService.regradeMySubmission(learner.getId(), UserRole.LEARNER, exam.getId());
		assertThat(pending.status()).isEqualTo(SubmissionStatus.SUBMITTED);
		assertThat(pending.submissionId()).isEqualTo(original.submissionId());
		assertThat(pending.attemptNo()).isEqualTo(1);
		assertThat(repeated).isEqualTo(pending);
		assertThat(pending.items()).allSatisfy(item -> {
			assertThat(item.score()).isNull();
			assertThat(item.verdict()).isNull();
			assertThat(item.feedback()).isNull();
		});
		doAnswer(invocation -> {
			GradeRequest request = invocation.getArgument(0);
			assertThat(request.studentAnswers()).containsExactly(
				new GradeRequest.StudentAnswer("q3", "Original answer"));
			return successfulGrade();
		}).when(aiClient).grade(any());
		worker.grade(original.submissionId());
		var graded = studentService.regradeMySubmission(learner.getId(), UserRole.LEARNER, exam.getId());
		assertThat(graded.status()).isEqualTo(SubmissionStatus.GRADED);
		assertThat(graded.score()).isEqualByComparingTo("30.00");
		assertThat(graded.submissionId()).isEqualTo(original.submissionId());
		assertThat(submissionRepository.findById(original.submissionId()).orElseThrow().getRequestId())
			.isEqualTo("recover-original");
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isEqualTo(1);
		verify(dispatcher, times(2)).dispatchAfterCommit(original.submissionId(), exam.getId());
		assertError(() -> submit("graded-change", "Changed answer"), ErrorCode.EXAM_ALREADY_SUBMITTED);
	}

	@Test
	void missingAiScoreIsNotInventedAndCanBeRegraded() {
		when(aiClient.grade(any())).thenReturn(new GradeResponse("1.0", exam.getId(), "SHORT",
			BigDecimal.ZERO, BigDecimal.TEN,
			List.of(new GradeResponse.Item("q3", null, BigDecimal.TEN, "WRONG", "Feedback")), null));
		var original = submit("missing-score", "Original answer");
		worker.grade(original.submissionId());
		assertFailedResultsHidden(original.submissionId());
		assertThat(answerRepository.findBySubmission_IdOrderByQuestion_Id(original.submissionId()))
			.filteredOn(answer -> answer.getQuestionNo() == 3).singleElement()
			.satisfies(answer -> assertThat(answer.getScore()).isNull());
		studentService.regradeMySubmission(learner.getId(), UserRole.LEARNER, exam.getId());
		doReturn(successfulGrade()).when(aiClient).grade(any());
		worker.grade(original.submissionId());
		assertThat(studentService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), null).status())
			.isEqualTo(SubmissionStatus.GRADED);
	}

	@Test
	void concurrentRecoveryRequestsAndWorkersOnlyGradeOneFixedAttempt() throws Exception {
		when(aiClient.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));
		var original = submit("concurrent-recovery", "Original answer");
		worker.grade(original.submissionId());
		clearInvocations(dispatcher, aiClient);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return studentService.regradeMySubmission(learner.getId(), UserRole.LEARNER, exam.getId());
			});
			var second = executor.submit(() -> {
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return studentService.regradeMySubmission(learner.getId(), UserRole.LEARNER, exam.getId());
			});
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS).submissionId()).isEqualTo(original.submissionId());
			assertThat(second.get(10, TimeUnit.SECONDS).submissionId()).isEqualTo(original.submissionId());
			verify(dispatcher).dispatchAfterCommit(original.submissionId(), exam.getId());

			CountDownLatch insideAi = new CountDownLatch(1);
			CountDownLatch releaseAi = new CountDownLatch(1);
			doAnswer(invocation -> {
				insideAi.countDown();
				assertThat(releaseAi.await(5, TimeUnit.SECONDS)).isTrue();
				return successfulGrade();
			}).when(aiClient).grade(any());
			var firstWorker = executor.submit(() -> worker.grade(original.submissionId()));
			try {
				assertThat(insideAi.await(5, TimeUnit.SECONDS)).isTrue();
				var secondWorker = executor.submit(() -> worker.grade(original.submissionId()));
				secondWorker.get(5, TimeUnit.SECONDS);
			} finally {
				releaseAi.countDown();
			}
			firstWorker.get(10, TimeUnit.SECONDS);
		}
		verify(aiClient).grade(any());
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isEqualTo(1);
		assertThat(studentService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), null).status())
			.isEqualTo(SubmissionStatus.GRADED);
	}

	@Test
	void retakeAllowedKeepsNewAttemptsButOldFailuresStayHiddenUntilClose() {
		exam.update(null, false, null, false, null, true, false, null);
		examRepository.saveAndFlush(exam);
		when(aiClient.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));
		var first = submit("old-failed", "First answer");
		worker.grade(first.submissionId());
		assertThat(studentService.detail(learner.getId(), UserRole.LEARNER, exam.getId()).submittable()).isTrue();
		var second = submit("retake-allowed", "Second answer");
		doReturn(successfulGrade()).when(aiClient).grade(any());
		worker.grade(second.submissionId());
		assertThat(second.attemptNo()).isEqualTo(2);
		assertFailedResultsHidden(first.submissionId());
		assertThat(studentService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), null).score())
			.isEqualByComparingTo("30.00");
		assertThat(submit("old-failed", "Changed answer").items())
			.allSatisfy(item -> assertThat(item.score()).isNull());
		exam.close(Instant.now());
		examRepository.saveAndFlush(exam);
		var review = studentService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), 1);
		assertThat(review.reviewAvailable()).isTrue();
		assertThat(review.items().get(0).score()).isEqualByComparingTo("10.00");
		assertThat(submit("old-failed", "Changed answer").items().get(0).score())
			.isEqualByComparingTo("10.00");
		assertError(() -> submit("closed-change", "Changed answer"), ErrorCode.EXAM_NOT_PUBLISHED);
	}

	@Test
	void instructorRecoveryAndManualAdjustmentRemainAvailable() {
		when(aiClient.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));
		var failed = submit("instructor-recovery", "Original answer");
		worker.grade(failed.submissionId());
		assertThat(instructorService.submissionDetail(instructor.getId(), UserRole.INSTRUCTOR,
			exam.getId(), failed.submissionId()).items().get(0).score()).isEqualByComparingTo("10.00");
		instructorService.regrade(instructor.getId(), UserRole.INSTRUCTOR, exam.getId(), failed.submissionId());
		doReturn(successfulGrade()).when(aiClient).grade(any());
		worker.grade(failed.submissionId());
		var adjusted = instructorService.adjustAnswerScore(instructor.getId(), UserRole.INSTRUCTOR,
			exam.getId(), failed.submissionId(), "q1", new ManualScoreAdjustmentRequest(new BigDecimal("8.00")));
		assertThat(adjusted.score()).isEqualByComparingTo("28.00");
		assertThat(studentService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), null).score())
			.isEqualByComparingTo("28.00");
		assertThat(studentService.detail(learner.getId(), UserRole.LEARNER, exam.getId()).submittable()).isFalse();
	}

	@Test
	void closedCompletedClassroomOnlyRecoversStoredAttempt() {
		when(aiClient.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));
		var original = submit("closed-recovery", "Original answer");
		worker.grade(original.submissionId());
		exam.close(Instant.now());
		examRepository.saveAndFlush(exam);
		Classroom classroom = classroomRepository.findById(exam.getClassroomId()).orElseThrow();
		classroom.complete();
		classroomRepository.saveAndFlush(classroom);

		var pending = studentService.regradeMySubmission(learner.getId(), UserRole.LEARNER, exam.getId());
		assertThat(pending.submissionId()).isEqualTo(original.submissionId());
		assertThat(pending.attemptNo()).isEqualTo(1);
		assertThat(pending.status()).isEqualTo(SubmissionStatus.SUBMITTED);
		doReturn(successfulGrade()).when(aiClient).grade(any());
		worker.grade(original.submissionId());
		var review = studentService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), null);
		assertThat(review.status()).isEqualTo(SubmissionStatus.GRADED);
		assertThat(review.reviewAvailable()).isTrue();
		assertThat(review.items().get(2).answer()).isEqualTo("Original answer");
		assertError(() -> submit("closed-new", "Changed answer"), ErrorCode.EXAM_NOT_PUBLISHED);
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isEqualTo(1);
	}

	@Test
	void rejectedRestoredDraftPreservesStartAndDraftButValidBodyWins() {
		studentService.startAttempt(learner.getId(), UserRole.LEARNER, exam.getId());
		Instant persistedStart = startRepository.findByExam_IdAndUser_Id(exam.getId(), learner.getId())
			.orElseThrow().getStartedAt();
		draftService.save(learner.getId(), UserRole.LEARNER, exam.getId(),
			new SaveExamAttemptDraftRequest(0, List.of(new ExamAnswerRequest("q3", "\u2003"))));
		var restored = draftService.get(learner.getId(), UserRole.LEARNER, exam.getId());
		assertError(() -> studentService.submit(learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest("restored-invalid", restored.answers())), ErrorCode.INVALID_EXAM_ANSWER);
		assertThat(draftService.get(learner.getId(), UserRole.LEARNER, exam.getId())).isEqualTo(restored);
		assertThat(startRepository.findByExam_IdAndUser_Id(exam.getId(), learner.getId()).orElseThrow().getStartedAt())
			.isEqualTo(persistedStart);
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isZero();
		var accepted = submit("body-wins", "Current body answer");
		assertThat(accepted.items().get(2).answer()).isEqualTo("Current body answer");
		assertThat(draftRepository.findByExam_IdAndUser_Id(exam.getId(), learner.getId())).isEmpty();
		assertThat(startRepository.findByExam_IdAndUser_Id(exam.getId(), learner.getId())).isEmpty();
	}

	@Test
	void studentRegradeRequiresLearnerMembershipAndAnExistingVisibleAttempt() {
		assertError(() -> studentService.regradeMySubmission(instructor.getId(), UserRole.INSTRUCTOR, exam.getId()),
			ErrorCode.ACCESS_DENIED);
		assertError(() -> studentService.regradeMySubmission(learner.getId(), UserRole.LEARNER, exam.getId()),
			ErrorCode.EXAM_NOT_FOUND);
		assertError(() -> studentService.regradeMySubmission(Long.MAX_VALUE, UserRole.LEARNER, exam.getId()),
			ErrorCode.CLASSROOM_NOT_FOUND);
		jdbcTemplate.update("update exams set status = 'DRAFT' where id = ?", exam.getId());
		assertError(() -> studentService.regradeMySubmission(learner.getId(), UserRole.LEARNER, exam.getId()),
			ErrorCode.EXAM_NOT_FOUND);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void concurrentSubmissionsPersistOneAttemptAndOneFixedAnswerSet(boolean sameRequestId) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		List<SubmissionOutcome> outcomes;
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> concurrentSubmit("concurrent-first", "First fixed answer", ready, start));
			var second = executor.submit(() -> concurrentSubmit(sameRequestId ? "concurrent-first" : "concurrent-second",
				"Second fixed answer", ready, start));
			try {
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			} finally {
				start.countDown();
			}
			outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
		}
		List<SubmissionOutcome> accepted = outcomes.stream().filter(result -> result.error() == null).toList();
		assertThat(accepted).hasSize(sameRequestId ? 2 : 1);
		if (!sameRequestId) {
			assertThat(outcomes).filteredOn(result -> result.error() != null).singleElement()
				.satisfies(result -> assertThat(result.error()).isEqualTo(ErrorCode.EXAM_ALREADY_SUBMITTED));
		}
		Long submissionId = accepted.getFirst().submissionId();
		assertThat(accepted).allSatisfy(result -> assertThat(result.submissionId()).isEqualTo(submissionId));
		assertThat(submissionRepository.countByExam_Id(exam.getId())).isEqualTo(1);
		var stored = submissionRepository.findById(submissionId).orElseThrow();
		assertThat(stored.getAttemptNo()).isEqualTo(1);
		assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
		var answers = answerRepository.findBySubmission_IdOrderByQuestion_Id(submissionId);
		assertThat(answers).hasSize(3);
		String fixedAnswer = answers.stream().filter(answer -> answer.getQuestionNo() == 3)
			.findFirst().orElseThrow().getAnswer();
		assertThat(fixedAnswer).isIn("First fixed answer", "Second fixed answer");
		assertThat(accepted).allSatisfy(result -> assertThat(result.answer()).isEqualTo(fixedAnswer));
		verify(dispatcher).dispatchAfterCommit(submissionId, exam.getId());
		verify(aiClient, never()).grade(any());
	}

	private SubmissionOutcome concurrentSubmit(String requestId, String answer, CountDownLatch ready,
		CountDownLatch start) throws InterruptedException {
		ready.countDown();
		assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
		try {
			var result = submit(requestId, answer);
			return new SubmissionOutcome(result.submissionId(), result.items().get(2).answer(), null);
		} catch (BusinessException exception) {
			return new SubmissionOutcome(null, null, exception.errorCode());
		}
	}

	private record SubmissionOutcome(Long submissionId, String answer, ErrorCode error) { }

	private GradeResponse successfulGrade() {
		return new GradeResponse("1.0", exam.getId(), "SHORT", BigDecimal.TEN, BigDecimal.TEN,
			List.of(new GradeResponse.Item("q3", BigDecimal.TEN, BigDecimal.TEN, "CORRECT", "Feedback")), null);
	}

	private void assertFailedResultsHidden(Long submissionId) {
		int attempt = submissionRepository.findById(submissionId).orElseThrow().getAttemptNo();
		var result = studentService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), attempt);
		assertThat(result.status()).isEqualTo(SubmissionStatus.GRADING_FAILED);
		assertThat(result.reviewAvailable()).isFalse();
		assertThat(result.score()).isNull();
		assertThat(result.normalizedScore()).isNull();
		assertThat(result.gradedAt()).isNull();
		assertThat(result.items()).allSatisfy(item -> {
			assertThat(item.score()).isNull();
			assertThat(item.verdict()).isNull();
			assertThat(item.feedback()).isNull();
		});
	}

	private io.edupilot.exam.dto.ExamSubmissionResponse submit(String requestId, String writtenAnswer) {
		return studentService.submit(learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest(requestId, List.of(new ExamAnswerRequest("q1", "a"),
				new ExamAnswerRequest("q2", "true"), new ExamAnswerRequest("q3", writtenAnswer))));
	}

	private void assertError(Runnable action, ErrorCode code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.errorCode()).isEqualTo(code));
	}
}
