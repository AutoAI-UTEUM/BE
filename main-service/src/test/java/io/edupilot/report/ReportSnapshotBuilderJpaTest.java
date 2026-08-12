package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.assessment.QuizAssessment;
import io.edupilot.assessment.QuizAssessmentData;
import io.edupilot.assessment.QuizAssessmentRepository;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.classroom.ClassroomWeekStatus;
import io.edupilot.classroom.ClassroomWeekMaterial;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.diagnosis.Diagnosis;
import io.edupilot.diagnosis.DiagnosisData;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.exam.Exam;
import io.edupilot.exam.ExamRepository;
import io.edupilot.exam.ExamSubmission;
import io.edupilot.exam.ExamSubmissionRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.memory.LearnerMemory;
import io.edupilot.memory.LearnerMemoryRepository;
import io.edupilot.quiz.GradingResult;
import io.edupilot.quiz.Quiz;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmission;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.quiz.QuizType;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.QaMessage;
import io.edupilot.session.QaMessageRepository;
import io.edupilot.session.QaThread;
import io.edupilot.session.QaThreadRepository;
import io.edupilot.session.SessionPageRecordRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:report-snapshot;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/report-snapshot"
	}
)
@ActiveProfiles("jpa-context")
@Transactional
class ReportSnapshotBuilderJpaTest {

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ClassroomWeekRepository weekRepository;
	@Autowired private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Autowired private LearningMaterialRepository materialRepository;
	@Autowired private LearningSessionRepository sessionRepository;
	@Autowired private ChatMessageRepository chatMessageRepository;
	@Autowired private QaThreadRepository qaThreadRepository;
	@Autowired private QaMessageRepository qaMessageRepository;
	@Autowired private QuizRepository quizRepository;
	@Autowired private QuizSubmissionRepository quizSubmissionRepository;
	@Autowired private QuizAssessmentRepository assessmentRepository;
	@Autowired private DiagnosisRepository diagnosisRepository;
	@Autowired private LearnerMemoryRepository memoryRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamSubmissionRepository examSubmissionRepository;
	@Autowired private ReportSnapshotBuilder snapshotBuilder;
	@Autowired private ReportCriterionCatalog criterionCatalog;
	@MockitoBean private SessionPageRecordRepository pageRecordRepository;

	private User instructor;
	private User otherInstructor;
	private User student;
	private User otherStudent;
	private Classroom classroom;
	private Classroom otherClassroom;
	private ClassroomWeek week;
	private LearningMaterial material;
	private LearningMaterial otherMaterial;
	private LearningSession session;

	@BeforeEach
	void setUp() {
		instructor = user("snapshot-instructor@example.com", "Instructor", UserRole.INSTRUCTOR);
		otherInstructor = user(
			"snapshot-other-instructor@example.com", "Other instructor", UserRole.INSTRUCTOR
		);
		student = user("snapshot-student@example.com", "Student", UserRole.LEARNER);
		otherStudent = user("snapshot-other@example.com", "Other student", UserRole.LEARNER);
		classroom = classroom(instructor, "Snapshot classroom", "SNAPSHOT123A");
		otherClassroom = classroom(
			otherInstructor, "Other classroom", "SNAPSHOT123B"
		);
		memberRepository.save(ClassroomMember.create(classroom, student, now()));
		memberRepository.save(ClassroomMember.create(classroom, otherStudent, now()));
		memberRepository.save(ClassroomMember.create(otherClassroom, student, now()));

		week = weekRepository.save(ClassroomWeek.create(
			classroom, 1, "Week 1", null, ClassroomWeekStatus.PRIVATE, 1
		));
		ClassroomWeek otherWeek = weekRepository.save(ClassroomWeek.create(
			otherClassroom, 1, "Other week", null, ClassroomWeekStatus.PUBLISHED, 1
		));
		material = readyMaterial(instructor, "Main material", "snapshot/main.pdf", 5);
		otherMaterial = readyMaterial(
			otherInstructor, "Other material", "snapshot/other.pdf", 7
		);
		weekMaterialRepository.save(ClassroomWeekMaterial.create(week, material, now()));
		weekMaterialRepository.save(ClassroomWeekMaterial.create(
			otherWeek, otherMaterial, now()
		));
		session = sessionRepository.saveAndFlush(LearningSession.create(student, material));
		when(pageRecordRepository.countDistinctByUserIdAndMaterialId(
			student.getId(), material.getId()
		)).thenReturn(2L);
	}

	@Test
	void collectsAllSevenSourcesOnceAndUsesRepresentativeQuizSubmission() {
		String longQuestion = "가".repeat(100);
		ChatMessage chatMessage = chatMessageRepository.saveAndFlush(ChatMessage.user(
			session, longQuestion, "question-1"
		));
		QaThread thread = qaThreadRepository.saveAndFlush(QaThread.start(session));
		qaMessageRepository.saveAndFlush(QaMessage.from(thread, chatMessage));

		Quiz quiz = quizRepository.saveAndFlush(Quiz.create(
			session, 1, "Quiz", 1, 1, QuizType.MCQ, List.of(), List.of(), "1.0"
		));
		quizSubmissionRepository.saveAndFlush(quizSubmission(
			quiz, student, "quiz-attempt-1", 1, "40"
		));
		QuizSubmission representative = quizSubmissionRepository.saveAndFlush(quizSubmission(
			quiz, student, "quiz-attempt-2", 2, "80"
		));
		assessmentRepository.saveAndFlush(QuizAssessment.create(
			session,
			representative,
			new QuizAssessmentData(
				"1.0", "Summary", List.of("strength"), List.of("weakness"),
				List.of("misconception"), "REVIEW", List.of(), List.of()
			)
		));
		diagnosisRepository.saveAndFlush(Diagnosis.pending(
			session,
			representative,
			"Internal prompt",
			new DiagnosisData(
				"1.0", List.of("concept"), List.of("misconception"),
				List.of("evidence"), "hint"
			)
		));
		LearnerMemory memory = LearnerMemory.create(student, material);
		ReflectionTestUtils.setField(memory, "strengths", List.of("strength"));
		ReflectionTestUtils.setField(memory, "weaknesses", List.of("weakness"));
		ReflectionTestUtils.setField(
			memory,
			"misconceptions",
			List.of("misconception")
		);
		ReflectionTestUtils.setField(memory, "targetDifficulty", "BALANCED");
		ReflectionTestUtils.setField(memory, "memoryDigest", "private digest");
		memoryRepository.saveAndFlush(memory);
		gradedSubmission(classroom, student, 1, "80");

		ReportSnapshot snapshot = build(classroom, instructor, student, ReportScope.full());

		assertThat(snapshot.dataQuality().availableSources())
			.containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ReportSourceType.class));
		assertThat(snapshot.metrics().quiz().cumulative().submissionCount()).isEqualTo(1);
		assertThat(snapshot.metrics().quiz().cumulative().averageNormalizedScore())
			.isEqualByComparingTo("80.00");
		assertThat(snapshot.metrics().progress())
			.isEqualTo(new ReportSnapshot.Progress(2, 5, 40, true));
		assertThat(snapshot.evidence()).extracting(ReportSnapshot.Evidence::evidenceId)
			.doesNotHaveDuplicates();
		String questionLabel = snapshot.evidence().stream()
			.filter(item -> item.sourceType() == ReportSourceType.QA_QUESTION)
			.findFirst().orElseThrow().publicLabel();
		assertThat(questionLabel.codePointCount(0, questionLabel.length())).isEqualTo(80);
		verify(pageRecordRepository).countDistinctByUserIdAndMaterialId(
			student.getId(), material.getId()
		);
	}

	@Test
	void isolatesOtherStudentAndOtherClassroomData() {
		LearningSession otherStudentSession = sessionRepository.saveAndFlush(
			LearningSession.create(otherStudent, material)
		);
		LearningSession otherClassroomSession = sessionRepository.saveAndFlush(
			LearningSession.create(student, otherMaterial)
		);
		ExamSubmission ownExam = gradedSubmission(classroom, student, 1, "80");
		ExamSubmission otherStudentExam = gradedSubmission(
			classroom, otherStudent, 1, "60"
		);
		ExamSubmission otherClassroomExam = gradedSubmission(
			otherClassroom, student, 1, "40"
		);

		ReportSnapshot snapshot = build(classroom, instructor, student, ReportScope.full());

		assertThat(snapshot.evidence()).extracting(ReportSnapshot.Evidence::sourceRef)
			.contains("session:" + session.getId(), "exam-submission:" + ownExam.getId())
			.doesNotContain(
				"session:" + otherStudentSession.getId(),
				"session:" + otherClassroomSession.getId(),
				"exam-submission:" + otherStudentExam.getId(),
				"exam-submission:" + otherClassroomExam.getId()
			);
		assertThat(snapshot.metrics().sessions().sessionCount()).isEqualTo(1);
		assertThat(snapshot.metrics().exam().cumulative().submissionCount()).isEqualTo(1);
	}

	@Test
	void usesLatestGradedExamAndExcludesFailedOnlyExams() {
		Exam exam = publishedExam(classroom, 1, "Representative exam");
		ExamSubmission graded = examSubmissionRepository.saveAndFlush(ExamSubmission.create(
			exam, student, 1, "graded-1", new BigDecimal("100"), now()
		));
		graded.complete(new BigDecimal("80"), new BigDecimal("80"), now());
		examSubmissionRepository.saveAndFlush(graded);
		ExamSubmission failedRetry = examSubmissionRepository.saveAndFlush(ExamSubmission.create(
			exam, student, 2, "failed-2", new BigDecimal("100"), now()
		));
		failedRetry.failGrading();
		examSubmissionRepository.saveAndFlush(failedRetry);

		Exam failedOnlyExam = publishedExam(classroom, 1, "Failed only exam");
		ExamSubmission failedOnly = examSubmissionRepository.saveAndFlush(ExamSubmission.create(
			failedOnlyExam, student, 1, "failed-only", new BigDecimal("100"), now()
		));
		failedOnly.failGrading();
		examSubmissionRepository.saveAndFlush(failedOnly);

		ReportSnapshot snapshot = build(classroom, instructor, student, ReportScope.full());

		assertThat(snapshot.metrics().exam().cumulative().submissionCount()).isEqualTo(1);
		assertThat(snapshot.metrics().exam().cumulative().averageNormalizedScore())
			.isEqualByComparingTo("80.00");
		assertThat(snapshot.evidence()).extracting(ReportSnapshot.Evidence::sourceRef)
			.contains("exam-submission:" + graded.getId())
			.doesNotContain(
				"exam-submission:" + failedRetry.getId(),
				"exam-submission:" + failedOnly.getId()
			);
	}

	@Test
	void rejectsNonManagingInstructorAndNonEnrolledStudentAsNotFound() {
		assertNotFound(() -> build(
			classroom, otherInstructor, student, ReportScope.full()
		), ErrorCode.CLASSROOM_NOT_FOUND);
		User nonMember = user("snapshot-non-member@example.com", "Non member", UserRole.LEARNER);
		assertNotFound(() -> build(
			classroom, instructor, nonMember, ReportScope.full()
		), ErrorCode.CLASSROOM_NOT_FOUND);
	}

	@Test
	void removedStudentCannotStartAnotherReportGeneration() {
		ClassroomMember membership = memberRepository
			.findByClassroom_IdAndUser_Id(classroom.getId(), student.getId())
			.orElseThrow();
		memberRepository.delete(membership);
		memberRepository.flush();

		assertNotFound(() -> snapshotBuilder.validateAccess(
			instructor.getId(), classroom.getId(), student.getId(), ReportScope.full()
		), ErrorCode.CLASSROOM_NOT_FOUND);
	}

	@Test
	void weekScopeExcludesOtherWeekAndZeroRecordsRemainUnavailable() {
		ClassroomWeek secondWeek = weekRepository.save(ClassroomWeek.create(
			classroom, 2, "Week 2", null, ClassroomWeekStatus.PUBLISHED, 2
		));
		LearningMaterial secondMaterial = readyMaterial(
			instructor, "Second material", "snapshot/second.pdf", 10
		);
		weekMaterialRepository.save(ClassroomWeekMaterial.create(
			secondWeek, secondMaterial, now()
		));
		LearningSession secondSession = sessionRepository.saveAndFlush(
			LearningSession.create(student, secondMaterial)
		);
		ExamSubmission weekOneExam = gradedSubmission(classroom, student, 1, "80");
		ExamSubmission weekTwoExam = gradedSubmission(classroom, student, 2, "90");
		when(pageRecordRepository.countDistinctByUserIdAndMaterialId(
			student.getId(), material.getId()
		)).thenReturn(0L);

		ReportSnapshot snapshot = build(classroom, instructor, student, ReportScope.week(1));

		assertThat(snapshot.metrics().progress())
			.isEqualTo(new ReportSnapshot.Progress(0, 5, 0, false));
		assertThat(snapshot.evidence()).extracting(ReportSnapshot.Evidence::sourceRef)
			.contains("session:" + session.getId(), "exam-submission:" + weekOneExam.getId())
			.doesNotContain(
				"session:" + secondSession.getId(),
				"exam-submission:" + weekTwoExam.getId()
			);
	}

	private ReportSnapshot build(
		Classroom targetClassroom,
		User targetInstructor,
		User targetStudent,
		ReportScope scope
	) {
		return snapshotBuilder.build(
			targetInstructor.getId(),
			targetClassroom.getId(),
			targetStudent.getId(),
			scope,
			criterionCatalog.defaultCriteria()
		);
	}

	private User user(String email, String name, UserRole role) {
		return userRepository.save(User.create(email, "hash", name, role));
	}

	private Classroom classroom(User owner, String name, String inviteCode) {
		return classroomRepository.save(Classroom.create(
			owner,
			name,
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			inviteCode
		));
	}

	private LearningMaterial readyMaterial(
		User owner,
		String title,
		String storageKey,
		int pageCount
	) {
		LearningMaterial ready = LearningMaterial.create(owner, title, storageKey);
		ready.markReady(pageCount);
		return materialRepository.save(ready);
	}

	private QuizSubmission quizSubmission(
		Quiz quiz,
		User learner,
		String requestId,
		int attemptNo,
		String score
	) {
		QuizSubmission submission = QuizSubmission.create(
			quiz,
			learner,
			requestId,
			List.of(),
			new GradingResult(
				"1.0", new BigDecimal(score), new BigDecimal("100"), List.of()
			),
			new BigDecimal(score).compareTo(new BigDecimal("60")) >= 0
		);
		ReflectionTestUtils.setField(submission, "attemptNo", attemptNo);
		return submission;
	}

	private ExamSubmission gradedSubmission(
		Classroom targetClassroom,
		User learner,
		int weekNumber,
		String score
	) {
		Exam exam = publishedExam(
			targetClassroom,
			weekNumber,
			"Exam-" + targetClassroom.getId() + "-" + learner.getId() + "-" + score
		);
		ExamSubmission submission = examSubmissionRepository.saveAndFlush(
			ExamSubmission.create(
				exam,
				learner,
				1,
				"exam-" + exam.getId() + "-" + learner.getId(),
				new BigDecimal("100"),
				now()
			)
		);
		submission.complete(new BigDecimal(score), new BigDecimal(score), now());
		return examSubmissionRepository.saveAndFlush(submission);
	}

	private Exam publishedExam(
		Classroom targetClassroom,
		int weekNumber,
		String title
	) {
		Exam exam = Exam.create(targetClassroom, weekNumber, title, null, true);
		exam.replaceTotalScore(new BigDecimal("100"));
		exam.publish(now());
		return examRepository.saveAndFlush(exam);
	}

	private Instant now() {
		return Instant.parse("2026-08-03T00:00:00Z");
	}

	private void assertNotFound(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}
}
