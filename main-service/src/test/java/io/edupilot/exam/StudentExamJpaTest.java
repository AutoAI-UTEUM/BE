package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

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
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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
			.doesNotContain("correctAnswer")
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
			new ExamPrivateAnswer(answer, "Explanation", null, List.of()),
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
			new ExamPrivateAnswer(answer, "Explanation", null, List.of()),
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
			new ExamPrivateAnswer("Reference", null, null, List.of()),
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
