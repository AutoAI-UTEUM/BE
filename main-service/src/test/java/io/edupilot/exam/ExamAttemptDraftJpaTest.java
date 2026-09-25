package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.dto.PermanentDeleteClassroomRequest;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.ExamAttemptDraftResponse;
import io.edupilot.exam.dto.SaveExamAttemptDraftRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.quiz.QuizOption;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:exam-attempt-draft;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/exam-attempt-draft"
	}
)
@ActiveProfiles("jpa-context")
class ExamAttemptDraftJpaTest {

	private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
	private static final AtomicInteger IDS = new AtomicInteger();

	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomService classroomService;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ExamRepository exams;
	@Autowired private ExamQuestionRepository questions;
	@Autowired private ExamSubmissionRepository submissions;
	@Autowired private ExamAttemptDraftRepository drafts;
	@Autowired private StudentExamService studentExamService;
	@Autowired private ExamAttemptDraftService draftService;
	@Autowired private ExamAttemptDraftCleanupScheduler cleanupScheduler;
	@Autowired private WebApplicationContext webContext;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@MockitoBean private AiClient aiClient;
	@MockitoBean private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
	}

	@Test
	void createsUpdatesAndReturnsLatestOnVersionConflict() {
		Fixture fixture = fixture(true, true);
		var first = draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(null, "first")
		);
		assertThat(first.version()).isOne();
		assertThat(draftService.get(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		).answers()).containsExactly(new ExamAnswerRequest("q1", "first"));

		when(clock.instant()).thenReturn(NOW.plusSeconds(5));
		var second = draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(1, "second")
		);
		assertThat(second.version()).isEqualTo(2);
		assertThat(second.savedAt()).isEqualTo(NOW.plusSeconds(5));

		assertThatThrownBy(() -> draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(1, "stale")
		)).isInstanceOfSatisfying(ExamDraftVersionConflictException.class, conflict -> {
			assertThat(conflict.latestDraft().version()).isEqualTo(2);
			assertThat(conflict.latestDraft().answers())
				.containsExactly(new ExamAnswerRequest("q1", "second"));
		});
		assertThat(draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(2, "resolved")
		).version()).isEqualTo(3);
		assertThat(draftService.get(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		).answers()).containsExactly(new ExamAnswerRequest("q1", "resolved"));
	}

	@Test
	void savesUnansweredItemAndResumesWithAnotherToken() throws Exception {
		Fixture fixture = fixture(true, true);
		Instant tokenNow = Instant.now();
		when(clock.instant()).thenReturn(tokenNow);
		String firstToken = jwtTokenProvider.createAccessToken(fixture.learner());
		when(clock.instant()).thenReturn(tokenNow.plusSeconds(1));
		String secondToken = jwtTokenProvider.createAccessToken(fixture.learner());
		assertThat(secondToken).isNotEqualTo(firstToken);
		var mockMvc = MockMvcBuilders.webAppContextSetup(webContext)
			.apply(springSecurity()).addFilters(traceIdFilter).build();
		String path = "/api/exams/" + fixture.exam().getId() + "/attempts/draft";
		mockMvc.perform(get(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
			.andExpect(status().isNoContent());
		mockMvc.perform(put(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"version\":0,\"answers\":[{\"questionId\":\"q1\",\"answer\":null}]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.version").value(1));
		mockMvc.perform(get(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.version").value(1))
			.andExpect(jsonPath("$.data.answers[0].questionId").value("q1"));
		ExamAttemptDraftResponse resumed = draftService.get(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		);
		assertThat(resumed.answers()).containsExactly(new ExamAnswerRequest("q1", null));
		assertThat(resumed.version()).isOne();
	}

	@Test
	void submissionConsumesDraftAndStartRemainsIdempotent() {
		Fixture fixture = fixture(true, true);
		var started = studentExamService.startAttempt(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		);
		assertThat(studentExamService.startAttempt(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		).startedAt()).isEqualTo(started.startedAt());
		draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(0, "a")
		);
		studentExamService.submit(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			new SubmitExamRequest(
				"draft-submit", List.of(new ExamAnswerRequest("q1", "a"))
			)
		);
		assertThat(submissions.existsByExam_IdAndUser_Id(
			fixture.exam().getId(), fixture.learner().getId()
		)).isTrue();
		assertThat(drafts.findByExam_IdAndUser_Id(
			fixture.exam().getId(), fixture.learner().getId()
		)).isEmpty();
		assertThat(draftService.get(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		)).isNull();
		assertError(() -> draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(0, "after submission")
		), ErrorCode.EXAM_ALREADY_SUBMITTED);
	}

	@Test
	void retakeDraftRequiresANewUnconsumedAttemptStart() {
		Fixture fixture = fixture(true, true);
		Long userId = fixture.learner().getId();
		Long examId = fixture.exam().getId();
		draftService.save(userId, UserRole.LEARNER, examId, request(0, "first"));
		var submitted = studentExamService.submit(
			userId, UserRole.LEARNER, examId,
			new SubmitExamRequest("first-attempt", List.of(new ExamAnswerRequest("q1", "a")))
		);
		assertThat(submitted.status()).isEqualTo(SubmissionStatus.GRADED);
		assertError(() -> draftService.save(
			userId, UserRole.LEARNER, examId, request(0, "before new start")
		), ErrorCode.EXAM_ALREADY_SUBMITTED);

		studentExamService.startAttempt(userId, UserRole.LEARNER, examId);
		assertThat(draftService.save(
			userId, UserRole.LEARNER, examId, request(0, "retake")
		).version()).isOne();
		assertThat(draftService.get(userId, UserRole.LEARNER, examId).answers())
			.containsExactly(new ExamAnswerRequest("q1", "retake"));
	}

	@Test
	void nonRetakeExamRejectsDraftAfterSubmissionEvenWithNewAttemptStart() {
		Fixture fixture = fixture(false, true);
		Long userId = fixture.learner().getId();
		Long examId = fixture.exam().getId();
		studentExamService.submit(
			userId, UserRole.LEARNER, examId,
			new SubmitExamRequest("non-retake", List.of(new ExamAnswerRequest("q1", "a")))
		);
		studentExamService.startAttempt(userId, UserRole.LEARNER, examId);
		assertError(() -> draftService.save(
			userId, UserRole.LEARNER, examId, request(0, "blocked")
		), ErrorCode.EXAM_ALREADY_SUBMITTED);
	}

	@Test
	void rejectsNonMemberInstructorClosedAndInvalidQuestionOrOversizedDraft() {
		Fixture fixture = fixture(true, true);
		User outsider = user("outsider", UserRole.LEARNER);
		assertError(() -> draftService.save(
			outsider.getId(), UserRole.LEARNER, fixture.exam().getId(), request(0, "a")
		), ErrorCode.CLASSROOM_NOT_FOUND);
		assertError(() -> draftService.save(
			fixture.instructor().getId(), UserRole.INSTRUCTOR,
			fixture.exam().getId(), request(0, "a")
		), ErrorCode.ACCESS_DENIED);
		assertError(() -> draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			new SaveExamAttemptDraftRequest(
				0, List.of(new ExamAnswerRequest("q2", "from other exam"))
			)
		), ErrorCode.INVALID_EXAM_ANSWER);
		assertError(() -> draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(0, "x".repeat(256 * 1024))
		), ErrorCode.DRAFT_TOO_LARGE);
		Fixture draft = fixture(true, false);
		assertError(() -> draftService.save(
			draft.learner().getId(), UserRole.LEARNER, draft.exam().getId(),
			request(0, "a")
		), ErrorCode.EXAM_NOT_FOUND);

		fixture.exam().close(NOW);
		exams.saveAndFlush(fixture.exam());
		assertError(() -> draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(0, "a")
		), ErrorCode.EXAM_NOT_PUBLISHED);
	}

	@Test
	void permanentClassroomDeleteRemovesDraftBeforeExam() {
		Fixture fixture = fixture(true, true);
		draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(0, "answer")
		);
		Long classroomId = fixture.classroom().getId();
		String classroomName = fixture.classroom().getName();
		classroomService.deletePermanently(
			fixture.instructor().getId(), UserRole.INSTRUCTOR, classroomId,
			new PermanentDeleteClassroomRequest(classroomName)
		);
		assertThat(drafts.findByExam_IdAndUser_Id(
			fixture.exam().getId(), fixture.learner().getId()
		)).isEmpty();
		assertThat(exams.findById(fixture.exam().getId())).isEmpty();
	}

	@Test
	void onlyOneConcurrentSaveCanAdvanceOneVersion() throws Exception {
		Fixture fixture = fixture(true, true);
		draftService.save(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId(),
			request(0, "initial")
		);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var task = (java.util.concurrent.Callable<Object>) () -> {
				ready.countDown();
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				try {
					return draftService.save(
						fixture.learner().getId(), UserRole.LEARNER,
						fixture.exam().getId(), request(1, "concurrent")
					);
				} catch (ExamDraftVersionConflictException conflict) {
					return conflict;
				}
			};
			var left = executor.submit(task);
			var right = executor.submit(task);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
				.filteredOn(item -> item instanceof ExamDraftVersionConflictException)
				.hasSize(1);
		} finally {
			executor.shutdownNow();
		}
		assertThat(draftService.get(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		).version()).isEqualTo(2);
	}

	@Test
	void cleanupDeletesOnlyDraftsNotUpdatedForThirtyDays() {
		Fixture stale = fixture(true, true);
		Fixture recent = fixture(true, true);
		draftService.save(stale.learner().getId(), UserRole.LEARNER,
			stale.exam().getId(), request(0, "old"));
		when(clock.instant()).thenReturn(NOW.plusSeconds(30L * 24 * 60 * 60));
		draftService.save(recent.learner().getId(), UserRole.LEARNER,
			recent.exam().getId(), request(0, "recent"));
		when(clock.instant()).thenReturn(NOW.plusSeconds(31L * 24 * 60 * 60));
		cleanupScheduler.cleanup();
		assertThat(drafts.findByExam_IdAndUser_Id(
			stale.exam().getId(), stale.learner().getId()
		)).isEmpty();
		assertThat(drafts.findByExam_IdAndUser_Id(
			recent.exam().getId(), recent.learner().getId()
		)).isPresent();
	}

	private Fixture fixture(boolean allowRetake, boolean published) {
		int id = IDS.incrementAndGet();
		User instructor = user("instructor-" + id, UserRole.INSTRUCTOR);
		User learner = user("learner-" + id, UserRole.LEARNER);
		Classroom classroom = classrooms.saveAndFlush(Classroom.create(
			instructor, "Draft classroom " + id,
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE, null, "DRAFT" + id
		));
		members.saveAndFlush(ClassroomMember.create(classroom, learner, NOW));
		Exam exam = Exam.create(classroom, 1, "Draft exam " + id, null, allowRetake);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		if (published) {
			exam.publish(NOW);
		}
		exam = exams.saveAndFlush(exam);
		questions.saveAndFlush(ExamQuestion.create(
			exam, 1, ExamQuestionType.MCQ, new BigDecimal("10.00"),
			new ExamPublicQuestion("문항", List.of(new QuizOption("a", "정답"))),
			new ExamPrivateAnswer("a", null, "해설", null, null, List.of()), "1.0"
		));
		if (published) {
			studentExamService.startAttempt(learner.getId(), UserRole.LEARNER, exam.getId());
		}
		return new Fixture(instructor, learner, classroom, exam);
	}

	private User user(String prefix, UserRole role) {
		return users.saveAndFlush(User.create(
			prefix + "@example.com", "hash", prefix, role
		));
	}

	private SaveExamAttemptDraftRequest request(Integer version, String answer) {
		return new SaveExamAttemptDraftRequest(
			version, List.of(new ExamAnswerRequest("q1", answer))
		);
	}

	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}

	private record Fixture(User instructor, User learner, Classroom classroom, Exam exam) {
	}
}
