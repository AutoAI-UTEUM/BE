package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.ai.AiClient;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.QuizOption;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:exam-attempt-timing;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/exam-attempt-timing"
	}
)
@ActiveProfiles("jpa-context")
class ExamAttemptTimingJpaTest {

	private static final Instant STARTED_AT = Instant.parse("2026-09-09T01:00:00Z");
	private static final AtomicInteger IDS = new AtomicInteger();

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamQuestionRepository questionRepository;
	@Autowired private ExamSubmissionRepository submissionRepository;
	@Autowired private ExamAttemptStartRepository attemptStartRepository;
	@Autowired private StudentExamService studentExamService;

	@MockitoBean private AiClient aiClient;
	@MockitoBean private Clock clock;

	@Test
	void repeatedAndConcurrentStartsReturnOneStableRecord() throws Exception {
		Fixture fixture = fixture(true, true);
		when(clock.instant()).thenReturn(STARTED_AT);

		var first = studentExamService.startAttempt(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		);
		var second = studentExamService.startAttempt(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		);

		assertThat(second.startedAt()).isEqualTo(first.startedAt());
		assertThat(attemptStartRepository.countByExam_IdAndUser_Id(
			fixture.exam().getId(), fixture.learner().getId()
		)).isOne();

		Fixture concurrentFixture = fixture(true, true);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var task = (java.util.concurrent.Callable<Instant>) () -> {
				ready.countDown();
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return studentExamService.startAttempt(
					concurrentFixture.learner().getId(),
					UserRole.LEARNER,
					concurrentFixture.exam().getId()
				).startedAt();
			};
			var left = executor.submit(task);
			var right = executor.submit(task);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(left.get(10, TimeUnit.SECONDS))
				.isEqualTo(right.get(10, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
		assertThat(attemptStartRepository.countByExam_IdAndUser_Id(
			concurrentFixture.exam().getId(), concurrentFixture.learner().getId()
		)).isOne();
	}

	@Test
	void submissionConsumesStartAndRetakeUsesNewTiming() {
		Fixture fixture = fixture(true, true);
		when(clock.instant()).thenReturn(STARTED_AT);
		studentExamService.startAttempt(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		);

		Instant firstSubmittedAt = STARTED_AT.plusSeconds(125);
		when(clock.instant()).thenReturn(firstSubmittedAt);
		var first = submit(fixture, "timed-attempt-1");
		ExamSubmission savedFirst = submissionRepository.findById(
			first.submissionId()
		).orElseThrow();
		assertThat(savedFirst.getStartedAt()).isEqualTo(STARTED_AT);
		assertThat(savedFirst.getDurationSeconds()).isEqualTo(125);
		assertThat(attemptStartRepository.findByExam_IdAndUser_Id(
			fixture.exam().getId(), fixture.learner().getId()
		)).isEmpty();

		Instant secondStartedAt = firstSubmittedAt.plusSeconds(60);
		when(clock.instant()).thenReturn(secondStartedAt);
		assertThat(studentExamService.startAttempt(
			fixture.learner().getId(), UserRole.LEARNER, fixture.exam().getId()
		).startedAt()).isEqualTo(secondStartedAt);

		when(clock.instant()).thenReturn(secondStartedAt.plusSeconds(30));
		var second = submit(fixture, "timed-attempt-2");
		ExamSubmission savedSecond = submissionRepository.findById(
			second.submissionId()
		).orElseThrow();
		assertThat(savedSecond.getStartedAt()).isEqualTo(secondStartedAt);
		assertThat(savedSecond.getDurationSeconds()).isEqualTo(30);
		assertThat(second.attemptNo()).isEqualTo(2);
		assertThat(attemptStartRepository.findByExam_IdAndUser_Id(
			fixture.exam().getId(), fixture.learner().getId()
		)).isEmpty();
	}

	@Test
	void submissionWithoutStartRemainsCompatibleAndNegativeDurationIsNullable() {
		Fixture withoutStart = fixture(false, true);
		when(clock.instant()).thenReturn(STARTED_AT);
		var legacy = submit(withoutStart, "legacy-without-start");
		ExamSubmission savedLegacy = submissionRepository.findById(
			legacy.submissionId()
		).orElseThrow();
		assertThat(savedLegacy.getStartedAt()).isNull();
		assertThat(savedLegacy.getDurationSeconds()).isNull();

		Fixture futureStart = fixture(false, true);
		when(clock.instant()).thenReturn(STARTED_AT.plusSeconds(60));
		studentExamService.startAttempt(
			futureStart.learner().getId(), UserRole.LEARNER, futureStart.exam().getId()
		);
		when(clock.instant()).thenReturn(STARTED_AT);
		var submitted = submit(futureStart, "negative-duration");
		ExamSubmission saved = submissionRepository.findById(
			submitted.submissionId()
		).orElseThrow();
		assertThat(saved.getStartedAt()).isEqualTo(STARTED_AT.plusSeconds(60));
		assertThat(saved.getDurationSeconds()).isNull();
		assertThat(attemptStartRepository.findByExam_IdAndUser_Id(
			futureStart.exam().getId(), futureStart.learner().getId()
		)).isEmpty();
	}

	@Test
	void startRejectsNonMemberInstructorDraftAndClosedExam() {
		Fixture published = fixture(false, true);
		User outsider = user("outsider", UserRole.LEARNER);
		assertError(
			() -> studentExamService.startAttempt(
				outsider.getId(), UserRole.LEARNER, published.exam().getId()
			),
			ErrorCode.CLASSROOM_NOT_FOUND
		);
		assertError(
			() -> studentExamService.startAttempt(
				published.instructor().getId(), UserRole.INSTRUCTOR, published.exam().getId()
			),
			ErrorCode.ACCESS_DENIED
		);

		Fixture draft = fixture(false, false);
		assertError(
			() -> studentExamService.startAttempt(
				draft.learner().getId(), UserRole.LEARNER, draft.exam().getId()
			),
			ErrorCode.EXAM_NOT_FOUND
		);

		published.exam().close(STARTED_AT);
		examRepository.saveAndFlush(published.exam());
		assertError(
			() -> studentExamService.startAttempt(
				published.learner().getId(), UserRole.LEARNER, published.exam().getId()
			),
			ErrorCode.EXAM_NOT_PUBLISHED
		);
	}

	private Fixture fixture(boolean allowRetake, boolean published) {
		int id = IDS.incrementAndGet();
		User instructor = user("instructor-" + id, UserRole.INSTRUCTOR);
		User learner = user("learner-" + id, UserRole.LEARNER);
		Classroom classroom = classroomRepository.saveAndFlush(Classroom.create(
			instructor,
			"Attempt classroom " + id,
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			"ATTEMPT" + id
		));
		memberRepository.saveAndFlush(ClassroomMember.create(
			classroom, learner, STARTED_AT.minusSeconds(1)
		));
		Exam exam = Exam.create(classroom, 1, "Attempt exam " + id, null, allowRetake);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		if (published) {
			exam.publish(STARTED_AT.minusSeconds(60));
		}
		exam = examRepository.saveAndFlush(exam);
		questionRepository.saveAndFlush(ExamQuestion.create(
			exam,
			1,
			ExamQuestionType.MCQ,
			new BigDecimal("10.00"),
			new ExamPublicQuestion("문항", List.of(new QuizOption("a", "정답"))),
			new ExamPrivateAnswer("a", null, "해설", null, null, List.of()),
			"1.0"
		));
		return new Fixture(instructor, learner, exam);
	}

	private User user(String prefix, UserRole role) {
		return userRepository.saveAndFlush(io.edupilot.VerifiedTestUsers.verified(User.create(
			prefix + "@example.com", "hash", prefix, role
		)));
	}

	private io.edupilot.exam.dto.ExamSubmissionResponse submit(
		Fixture fixture,
		String requestId
	) {
		return studentExamService.submit(
			fixture.learner().getId(),
			UserRole.LEARNER,
			fixture.exam().getId(),
			new SubmitExamRequest(
				requestId, List.of(new ExamAnswerRequest("q1", "a"))
			)
		);
	}

	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}

	private record Fixture(User instructor, User learner, Exam exam) {
	}
}
