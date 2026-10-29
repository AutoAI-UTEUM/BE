package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientProperties;
import io.edupilot.ai.HttpAiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageLog;
import io.edupilot.aiusage.AiUsageLogRepository;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.notification.ExamNotificationDispatcher;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import tools.jackson.databind.ObjectMapper;

/**
 * Synthetic worker + real service/JPA acceptance. No provider calls, operating
 * policy changes, quota reservations or estimates of missing billing data.
 */
@SpringBootTest(classes = io.edupilot.MainServiceApplication.class, properties = {
	"spring.datasource.url=jdbc:h2:mem:worker-usage-acceptance;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/worker-usage-acceptance",
	"edupilot.mail.enabled=false"
})
@ActiveProfiles("jpa-context")
class WorkerUsageAcceptanceJpaTest {

	private static final Instant NOW = Instant.parse("2026-10-08T08:00:00Z");
	private static final LocalDateTime FROM = LocalDateTime.of(2020, 1, 1, 0, 0);
	private static final LocalDateTime TO = LocalDateTime.of(2030, 1, 1, 0, 0);
	private static final AiUsage KNOWN = new AiUsage("synthetic-model", 10L, 20L, 0L, 123L);

	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ExamRepository exams;
	@Autowired private ExamQuestionRepository questions;
	@Autowired private ExamSubmissionRepository submissions;
	@Autowired private ExamAnswerRepository answers;
	@Autowired private ExamSubmissionPersistenceService persistence;
	@Autowired private ExamAiGradingService grading;
	@Autowired private ExamGradingProperties properties;
	@Autowired private AiUsageLogRepository usage;
	@Autowired private AiClientProperties clientProperties;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private AiClient ai;
	@MockitoBean private Clock clock;
	@MockitoBean private ExamGradingDispatcher dispatcher;
	@MockitoBean private ExamGradingRecoveryScheduler scheduler;
	@MockitoSpyBean private ExamNotificationDispatcher notifications;

	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	private User learner;
	private Exam exam;
	private ExamSubmission submission;

	@BeforeEach
	void createIsolatedSyntheticSubmission() {
		usage.deleteAll();
		now.set(NOW);
		when(clock.instant()).thenAnswer(ignored -> now.get());
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		User instructor = users.saveAndFlush(io.edupilot.VerifiedTestUsers.legacyVerified(
			User.create("worker-instructor-" + suffix + "@example.test", "!synthetic",
				"Synthetic instructor", UserRole.INSTRUCTOR)));
		learner = users.saveAndFlush(io.edupilot.VerifiedTestUsers.legacyVerified(
			User.create("worker-learner-" + suffix + "@example.test", "!synthetic",
				"Synthetic learner", UserRole.LEARNER)));
		Classroom classroom = classrooms.saveAndFlush(Classroom.create(instructor,
			"Synthetic worker usage", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE, null, "WU" + suffix));
		exam = Exam.create(classroom, 1, "Synthetic exam", null, false);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		exam.publish(NOW.minusSeconds(60));
		exam = exams.saveAndFlush(exam);
		ExamQuestion question = questions.saveAndFlush(ExamQuestion.create(exam, 1,
			ExamQuestionType.SHORT, new BigDecimal("10.00"),
			new ExamPublicQuestion("Synthetic question", List.of()),
			new ExamPrivateAnswer(null, null, null, "Synthetic reference", null, List.of()), "1.0"));
		submission = submissions.saveAndFlush(ExamSubmission.create(exam, learner, 1,
			"worker-" + suffix, new BigDecimal("10.00"), NOW));
		answers.saveAndFlush(ExamAnswer.create(submission, question, "Synthetic answer",
			new BigDecimal("10.00")));
	}

	@Test
	void decodedUsageOnAiFailureSurvivesFailedGradingWithoutInventingCost() {
		when(ai.grade(any())).thenThrow(
			new AiClientException(ErrorCode.AI_RESPONSE_INVALID).withUsage(KNOWN));

		newWorker().grade(submission.getId());

		assertFailedWithoutAppliedAnswer();
		assertThat(usage.findAll()).singleElement().satisfies(log -> {
			assertThat(log.getUserId()).isEqualTo(learner.getId());
			assertThat(log.getFeature()).isEqualTo(AiFeature.GRADE);
			assertThat(log.isSuccess()).isFalse();
			assertThat(log.getModel()).isEqualTo("synthetic-model");
			assertThat(log.getInputTokens()).isEqualTo(10L);
			assertThat(log.getOutputTokens()).isEqualTo(20L);
			assertThat(log.getReasoningTokens()).isZero();
			assertThat(log.getCostUsdTicks()).isEqualTo(123L);
		});
		assertCost(123L, 1, 0);
	}

	@Test
	void timeoutThenExplicitRegradeKeepsUnknownSeparateFromVerifiedZero() {
		when(ai.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));

		newWorker().grade(submission.getId());

		assertFailedWithoutAppliedAnswer();
		AiUsageLog unknown = usage.findAll().get(0);
		assertThat(unknown.getCostUsdTicks()).isNull();
		assertThat(unknown.getInputTokens()).isNull();
		assertThat(unknown.getOutputTokens()).isNull();
		assertThat(usage.summarizeXaiCost(FROM, TO).getCostUsdTicks()).isNull();
		assertThat(usage.aggregateByFeature(FROM, TO)).singleElement()
			.satisfies(row -> {
				assertThat(row.getInputTokens()).isNull();
				assertThat(row.getOutputTokens()).isNull();
				assertThat(row.getReasoningTokens()).isNull();
			});
		assertCost(null, 0, 1);

		persistence.regradeFailedSubmission(exam.getId(), submission.getId());
		doReturn(response("0.00",
			new AiUsage("synthetic-zero", 0L, 0L, 0L, 0L))).when(ai).grade(any());
		newWorker().grade(submission.getId());

		assertGraded("0.00");
		assertThat(usage.findAll()).hasSize(2);
		assertThat(usage.findById(unknown.getId()).orElseThrow()).satisfies(log -> {
			assertThat(log.isSuccess()).isFalse();
			assertThat(log.getCreatedAt()).isEqualTo(unknown.getCreatedAt());
			assertThat(log.getCostUsdTicks()).isNull();
		});
		assertThat(usage.findAll()).filteredOn(AiUsageLog::isSuccess).singleElement()
			.satisfies(log -> {
				assertThat(log.getCostUsdTicks()).isZero();
				assertThat(log.getInputTokens()).isZero();
				assertThat(log.getOutputTokens()).isZero();
			});
		assertCost(0L, 1, 1);
	}

	@Test
	void resultTransactionRollbackKeepsUsageAndExplicitRegradeRecordsANewAttempt() {
		when(ai.grade(any())).thenReturn(response("8.00", KNOWN));
		doThrow(new IllegalStateException("synthetic result transaction fault"))
			.when(notifications).gradedAfterCommit(anyLong(), anyLong(), anyLong(), anyLong());

		newWorker().grade(submission.getId());

		// The failure is injected after answer/submission flush, before commit.
		assertFailedWithoutAppliedAnswer();
		AiUsageLog original = usage.findAll().get(0);
		assertThat(original.isSuccess()).isTrue(); // successful upstream call, not accepted grade
		assertCost(123L, 1, 0);

		doCallRealMethod().when(notifications)
			.gradedAfterCommit(anyLong(), anyLong(), anyLong(), anyLong());
		persistence.regradeFailedSubmission(exam.getId(), submission.getId());
		when(ai.grade(any())).thenReturn(response("9.00",
			new AiUsage("synthetic-regrade", 7L, 3L, null, 77L)));
		newWorker().grade(submission.getId());

		assertGraded("9.00");
		assertThat(usage.findAll()).hasSize(2);
		assertThat(usage.findById(original.getId()).orElseThrow()).satisfies(log -> {
			assertThat(log.getCreatedAt()).isEqualTo(original.getCreatedAt());
			assertThat(log.getCostUsdTicks()).isEqualTo(123L);
		});
		assertCost(200L, 2, 0);
		assertThat(gradedNotifications()).isEqualTo(1);
	}

	@Test
	void supersededWorkerLateResponseCannotReplaceNewGradeAndBothAttemptsStayRecorded()
		throws Exception {
		CountDownLatch oldEnteredAi = new CountDownLatch(1);
		CountDownLatch releaseOldAi = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		when(ai.grade(any())).thenAnswer(ignored -> {
			if (calls.incrementAndGet() == 1) {
				oldEnteredAi.countDown();
				if (!releaseOldAi.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Synthetic old worker barrier timed out");
				}
				return response("2.00", new AiUsage("synthetic-old", 1L, 2L, null, 111L));
			}
			return response("9.00", new AiUsage("synthetic-new", 3L, 4L, null, 222L));
		});

		try (var executor = Executors.newSingleThreadExecutor()) {
			var oldWorker = executor.submit(() -> newWorker().grade(submission.getId()));
			try {
				assertThat(oldEnteredAi.await(10, TimeUnit.SECONDS)).isTrue();
				now.set(NOW.plus(properties.leaseDuration()).plusSeconds(1));
				// New worker instance uses only durable JPA state after old lease expiry.
				newWorker().grade(submission.getId());
				assertGraded("9.00");
			} finally {
				releaseOldAi.countDown();
			}
			oldWorker.get(10, TimeUnit.SECONDS);
		}

		assertGraded("9.00");
		assertThat(calls.get()).isEqualTo(2);
		assertThat(usage.findAll()).hasSize(2).allSatisfy(log ->
			assertThat(log.isSuccess()).isTrue());
		assertCost(333L, 2, 0);
		assertThat(gradedNotifications()).isEqualTo(1);
	}

	@Test
	void consentEpochChangeDuringAiRejectsLateGradeWhileKeepingAlreadyReportedUsage() {
		when(ai.grade(any())).thenAnswer(ignored -> {
			assertThat(jdbc.update(
				"update users set guardian_consent_epoch = guardian_consent_epoch + 1 where id = ?",
				learner.getId())).isEqualTo(1);
			return response("8.00", KNOWN);
		});

		newWorker().grade(submission.getId());

		assertFailedWithoutAppliedAnswer();
		assertThat(usage.findAll()).singleElement().satisfies(log -> {
			assertThat(log.isSuccess()).isTrue(); // received usage; no fabricated provider refund
			assertThat(log.getCostUsdTicks()).isEqualTo(123L);
		});
		assertCost(123L, 1, 0);
		assertThat(gradedNotifications()).isZero();
	}

	@Test
	void decodedHttpSchemaFailurePreservesKnownUsageThroughWorkerAndJpa() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start(InetAddress.getLoopbackAddress(), 0);
			GradeResponse valid = response("8.00", KNOWN);
			GradeResponse invalidSchema = new GradeResponse("synthetic-unsupported",
				valid.quizId(), valid.quizType(), valid.score(), valid.maxScore(),
				valid.items(), valid.usage());
			server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(new ObjectMapper().writeValueAsString(invalidSchema)));
			HttpAiClient localClient = new HttpAiClient(localHttpProperties(server.url("/").uri()));
			when(ai.grade(any())).thenAnswer(call -> localClient.grade(call.getArgument(0)));

			newWorker().grade(submission.getId());

			assertFailedWithoutAppliedAnswer();
			assertThat(usage.findAll()).singleElement().satisfies(log -> {
				assertThat(log.isSuccess()).isFalse();
				assertThat(log.getModel()).isEqualTo(KNOWN.model());
				assertThat(log.getInputTokens()).isEqualTo(KNOWN.inputTokens());
				assertThat(log.getOutputTokens()).isEqualTo(KNOWN.outputTokens());
				assertThat(log.getCostUsdTicks()).isEqualTo(KNOWN.costUsdTicks());
			});
			assertCost(123L, 1, 0);
			var request = server.takeRequest(5, TimeUnit.SECONDS);
			assertThat(request).isNotNull();
			assertThat(request.getPath()).isEqualTo("/internal/ai/grade");
			assertThat(server.getRequestCount()).isEqualTo(1); // schema failure is never replayed
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"UNKNOWN_USAGE", "UNDECODABLE_JSON"})
	void httpFailureWithoutDecodedUsageRemainsUnknown(String failure) throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start(InetAddress.getLoopbackAddress(), 0);
			String body;
			if ("UNDECODABLE_JSON".equals(failure)) {
				body = "{synthetic malformed JSON";
			} else {
				GradeResponse valid = response("8.00", null);
				body = new ObjectMapper().writeValueAsString(new GradeResponse(
					"synthetic-unsupported", valid.quizId(), valid.quizType(), valid.score(),
					valid.maxScore(), valid.items(), null));
			}
			server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(body));
			HttpAiClient localClient = new HttpAiClient(localHttpProperties(server.url("/").uri()));
			when(ai.grade(any())).thenAnswer(call -> localClient.grade(call.getArgument(0)));

			newWorker().grade(submission.getId());

			assertFailedWithoutAppliedAnswer();
			assertThat(usage.findAll()).singleElement().satisfies(log -> {
				assertThat(log.isSuccess()).isFalse();
				assertThat(log.getModel()).isNull();
				assertThat(log.getInputTokens()).isNull();
				assertThat(log.getOutputTokens()).isNull();
				assertThat(log.getCostUsdTicks()).isNull();
			});
			assertCost(null, 0, 1);
			assertThat(server.getRequestCount()).isEqualTo(1);
		}
	}

	private AiClientProperties localHttpProperties(URI baseUrl) {
		return new AiClientProperties(baseUrl, "synthetic-local-token",
			clientProperties.connectTimeout(), clientProperties.healthTimeout(),
			clientProperties.readTimeout(), clientProperties.turnReadTimeout(),
			clientProperties.streamIdleTimeout(), clientProperties.gradeReadTimeout(),
			clientProperties.pipelineReadTimeout(), clientProperties.assessmentReadTimeout(),
			clientProperties.diagnosisReadTimeout(), clientProperties.extractReadTimeout(),
			clientProperties.xaiFileUploadTimeout(), clientProperties.reportReadTimeout(),
			clientProperties.reportQueryReadTimeout(), clientProperties.criteriaReadTimeout(),
			clientProperties.outlineTimeout(), clientProperties.captionsReadTimeout(),
			clientProperties.docChatReadTimeout(), clientProperties.summaryReadTimeout(),
			clientProperties.examDraftReadTimeout(), clientProperties.healthPath());
	}

	private ExamGradingWorker newWorker() {
		return new ExamGradingWorker(persistence, grading, properties, clock);
	}

	private GradeResponse response(String score, AiUsage reportedUsage) {
		BigDecimal points = new BigDecimal(score);
		String verdict = points.signum() == 0 ? "WRONG" : "PARTIAL";
		return new GradeResponse("1.0", exam.getId(), "SHORT", points,
			new BigDecimal("10.00"), List.of(new GradeResponse.Item(
				"q1", points, new BigDecimal("10.00"), verdict, "Synthetic feedback")),
			reportedUsage);
	}

	private void assertFailedWithoutAppliedAnswer() {
		assertThat(submissions.findById(submission.getId()).orElseThrow()).satisfies(stored -> {
			assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.GRADING_FAILED);
			assertThat(stored.getScore()).isNull();
			assertThat(stored.getGradedAt()).isNull();
			assertThat(stored.getGradingLeaseToken()).isNull();
		});
		assertThat(answers.findBySubmission_IdOrderByQuestion_Id(submission.getId()))
			.singleElement().satisfies(answer -> assertThat(answer.getScore()).isNull());
	}

	private void assertGraded(String score) {
		assertThat(submissions.findById(submission.getId()).orElseThrow()).satisfies(stored -> {
			assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.GRADED);
			assertThat(stored.getScore()).isEqualByComparingTo(score);
			assertThat(stored.getGradingLeaseToken()).isNull();
		});
		assertThat(answers.findBySubmission_IdOrderByQuestion_Id(submission.getId()))
			.singleElement().satisfies(answer ->
				assertThat(answer.getScore()).isEqualByComparingTo(score));
	}

	private void assertCost(Long knownCost, long knownCalls, long unknownCalls) {
		var cost = usage.summarizeXaiCost(FROM, TO);
		if (knownCost == null) {
			assertThat(cost.getCostUsdTicks()).isNull();
		} else {
			assertThat(cost.getCostUsdTicks()).isEqualByComparingTo(BigDecimal.valueOf(knownCost));
		}
		assertThat(cost.getKnownCostCalls()).isEqualTo(knownCalls);
		assertThat(cost.getUnknownCostCalls()).isEqualTo(unknownCalls);
	}

	private int gradedNotifications() {
		return jdbc.queryForObject(
			"select count(*) from notifications where dedup_key = ?",
			Integer.class, "EXAM_GRADED:" + submission.getId() + ":" + learner.getId());
	}
}
