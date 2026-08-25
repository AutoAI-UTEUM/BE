package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.assessment.QuizAssessment;
import io.edupilot.assessment.QuizAssessmentData;
import io.edupilot.assessment.QuizAssessmentRepository;
import io.edupilot.classroom.dto.PermanentDeleteClassroomRequest;
import io.edupilot.diagnosis.Diagnosis;
import io.edupilot.diagnosis.DiagnosisData;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.exam.Exam;
import io.edupilot.exam.ExamAnswer;
import io.edupilot.exam.ExamAnswerRepository;
import io.edupilot.exam.ExamPrivateAnswer;
import io.edupilot.exam.ExamPublicQuestion;
import io.edupilot.exam.ExamQuestion;
import io.edupilot.exam.ExamQuestionRepository;
import io.edupilot.exam.ExamQuestionType;
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
import io.edupilot.report.ReportCriterion;
import io.edupilot.report.ReportCriterionRepository;
import io.edupilot.report.ReportCriterionResult;
import io.edupilot.report.ReportCriterionResultRepository;
import io.edupilot.report.ReportCriterionStatus;
import io.edupilot.report.ReportEvidenceSnapshot;
import io.edupilot.report.ReportEvidenceSnapshotRepository;
import io.edupilot.report.ReportGeneration;
import io.edupilot.report.ReportGenerationRepository;
import io.edupilot.report.ReportScopeType;
import io.edupilot.report.StudentReport;
import io.edupilot.report.StudentReportRepository;
import io.edupilot.schedule.UserSchedule;
import io.edupilot.schedule.UserScheduleRepository;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.QaMessage;
import io.edupilot.session.QaMessageRepository;
import io.edupilot.session.QaThread;
import io.edupilot.session.QaThreadRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:classroom-permanent-delete;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/classroom-permanent-delete"
	}
)
@ActiveProfiles("jpa-context")
@Transactional
class ClassroomPermanentDeleteJpaTest {

	private static final Instant NOW = Instant.parse("2026-08-05T03:00:00Z");

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ClassroomJoinRequestRepository joinRequestRepository;
	@Autowired private ClassroomWeekRepository weekRepository;
	@Autowired private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Autowired private ClassroomNoticeRepository noticeRepository;
	@Autowired private ClassroomResourceRepository resourceRepository;
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
	@Autowired private UserScheduleRepository scheduleRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamQuestionRepository examQuestionRepository;
	@Autowired private ExamSubmissionRepository examSubmissionRepository;
	@Autowired private ExamAnswerRepository examAnswerRepository;
	@Autowired private ReportCriterionRepository reportCriterionRepository;
	@Autowired private ReportGenerationRepository reportGenerationRepository;
	@Autowired private StudentReportRepository studentReportRepository;
	@Autowired private ReportCriterionResultRepository reportResultRepository;
	@Autowired private ReportEvidenceSnapshotRepository evidenceRepository;
	@Autowired private ClassroomService classroomService;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void createSessionPageRecordTable() {
		jdbcTemplate.execute("""
			create table if not exists session_page_records (
				id bigint generated by default as identity primary key,
				session_id bigint not null,
				page_number int not null,
				explained_at timestamp(6) not null,
				created_at timestamp(6) not null,
				updated_at timestamp(6) not null,
				constraint fk_test_page_record_session
					foreign key (session_id) references learning_sessions(id),
				constraint uk_test_page_record_session_page
					unique (session_id, page_number)
			)
			""");
	}

	@Test
	void deletesEntireClassroomGraphAndPreservesMaterialLearningAndScheduleData() {
		User instructor = user("delete-instructor@example.com", "Instructor", UserRole.INSTRUCTOR);
		User learner = user("delete-learner@example.com", "Learner", UserRole.LEARNER);
		Classroom classroom = classroom(instructor, "Delete classroom", "DELETE187");
		memberRepository.save(ClassroomMember.create(classroom, learner, NOW));
		joinRequestRepository.save(ClassroomJoinRequest.create(classroom, learner, NOW));

		LearningMaterial material = LearningMaterial.create(
			instructor, "Preserved material", "materials/preserved.pdf"
		);
		material.markReady(3);
		material = materialRepository.saveAndFlush(material);
		ClassroomWeek week = weekRepository.save(ClassroomWeek.create(
			classroom,
			1,
			"Week 1",
			null,
			ClassroomWeekStatus.PUBLISHED,
			1
		));
		weekMaterialRepository.save(ClassroomWeekMaterial.create(week, material, NOW));
		noticeRepository.save(ClassroomNotice.create(
			classroom, "Notice", "Delete with classroom", NOW
		));
		resourceRepository.save(ClassroomResource.link(
			classroom,
			"Reference",
			1,
			"https://example.com/reference"
		));

		LearningSession session = sessionRepository.saveAndFlush(
			LearningSession.create(learner, material)
		);
		jdbcTemplate.update(
			"""
				insert into session_page_records (
					session_id, page_number, explained_at, created_at, updated_at
				) values (?, ?, ?, ?, ?)
				""",
			session.getId(), 1, NOW, NOW, NOW
		);
		ChatMessage chatMessage = chatMessageRepository.saveAndFlush(
			ChatMessage.user(session, "Preserved question", "preserved-question")
		);
		QaThread qaThread = qaThreadRepository.saveAndFlush(QaThread.start(session));
		QaMessage qaMessage = qaMessageRepository.saveAndFlush(
			QaMessage.from(qaThread, chatMessage)
		);
		Quiz quiz = quizRepository.saveAndFlush(Quiz.create(
			session, 1, "Preserved quiz", 1, 1, QuizType.MCQ,
			List.of(), List.of(), "1.0"
		));
		QuizSubmission quizSubmission = quizSubmissionRepository.saveAndFlush(
			QuizSubmission.create(
				quiz,
				learner,
				"preserved-submission",
				List.of(),
				new GradingResult(
					"1.0", BigDecimal.ONE, BigDecimal.ONE, List.of()
				),
				true
			)
		);
		QuizAssessment assessment = assessmentRepository.saveAndFlush(
			QuizAssessment.create(
				session,
				quizSubmission,
				new QuizAssessmentData(
					"1.0", "Summary", List.of(), List.of(), List.of(),
					"CONTINUE", List.of(), List.of()
				)
			)
		);
		Diagnosis diagnosis = diagnosisRepository.saveAndFlush(Diagnosis.pending(
			session,
			quizSubmission,
			"Prompt",
			new DiagnosisData("1.0", List.of(), List.of(), List.of(), "Hint")
		));
		LearnerMemory memory = LearnerMemory.create(learner, material);
		ReflectionTestUtils.setField(
			memory,
			"memoryDigest",
			"Preserved digest"
		);
		memory = memoryRepository.saveAndFlush(memory);
		UserSchedule schedule = scheduleRepository.saveAndFlush(UserSchedule.create(
			learner, "Preserved schedule", NOW, NOW.plusSeconds(3600), true
		));

		createExamGraph(classroom, learner);
		createReportGraph(classroom, instructor, learner);

		classroomService.deletePermanently(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.getId(),
			new PermanentDeleteClassroomRequest("Delete classroom")
		);

		assertDeletedTablesAreEmpty();
		assertThat(materialRepository.existsById(material.getId())).isTrue();
		assertThat(sessionRepository.existsById(session.getId())).isTrue();
		assertThat(count("session_page_records")).isOne();
		assertThat(chatMessageRepository.existsById(chatMessage.getId())).isTrue();
		assertThat(qaThreadRepository.existsById(qaThread.getId())).isTrue();
		assertThat(qaMessageRepository.existsById(qaMessage.getId())).isTrue();
		assertThat(quizRepository.existsById(quiz.getId())).isTrue();
		assertThat(quizSubmissionRepository.existsById(quizSubmission.getId())).isTrue();
		assertThat(assessmentRepository.existsById(assessment.getId())).isTrue();
		assertThat(diagnosisRepository.existsById(diagnosis.getId())).isTrue();
		assertThat(memoryRepository.existsById(memory.getId())).isTrue();
		assertThat(scheduleRepository.existsById(schedule.getId())).isTrue();

		assertThatThrownBy(() -> classroomService.deletePermanently(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.getId(),
			new PermanentDeleteClassroomRequest("Delete classroom")
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLASSROOM_NOT_FOUND)
		);
	}

	@Test
	void completedClassroomCanBeDeletedPermanently() {
		User instructor = user("completed-instructor@example.com", "Instructor", UserRole.INSTRUCTOR);
		Classroom classroom = classroom(instructor, "Completed classroom", "COMPLETE187");
		classroom.complete();
		classroomRepository.flush();

		classroomService.deletePermanently(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.getId(),
			new PermanentDeleteClassroomRequest("Completed classroom")
		);

		assertThat(classroomRepository.existsById(classroom.getId())).isFalse();
	}

	private void createExamGraph(Classroom classroom, User learner) {
		Exam exam = examRepository.save(Exam.create(
			classroom, 1, "Exam", null, false
		));
		ExamQuestion question = examQuestionRepository.save(ExamQuestion.create(
			exam,
			1,
			ExamQuestionType.MCQ,
			BigDecimal.ONE,
			new ExamPublicQuestion("Question", List.of()),
			new ExamPrivateAnswer("A", null, "Explanation", null, null, List.of()),
			"1.0"
		));
		ExamSubmission submission = examSubmissionRepository.save(ExamSubmission.create(
			exam, learner, 1, "exam-request", BigDecimal.ONE, NOW
		));
		examAnswerRepository.saveAndFlush(ExamAnswer.create(
			submission, question, "A", BigDecimal.ONE
		));
	}

	private void createReportGraph(
		Classroom classroom,
		User instructor,
		User learner
	) {
		reportCriterionRepository.save(ReportCriterion.create(
			classroom,
			"custom",
			"Custom",
			null,
			Map.of("description", "Rubric"),
			List.of("SESSION"),
			1,
			BigDecimal.ONE,
			1,
			true
		));
		ReportGeneration firstGeneration = reportGenerationRepository.save(
			ReportGeneration.create(
				classroom, learner, instructor, "report-1", ReportScopeType.FULL,
				null, "scope-1", "1.0"
			)
		);
		ReportGeneration secondGeneration = reportGenerationRepository.save(
			ReportGeneration.create(
				classroom, learner, instructor, "report-2", ReportScopeType.FULL,
				null, "scope-2", "1.0"
			)
		);
		StudentReport first = studentReportRepository.saveAndFlush(StudentReport.create(
			firstGeneration,
			classroom,
			learner,
			1,
			null,
			new BigDecimal("80.00"),
			"GOOD",
			"First",
			Map.of("available", true),
			"test-model",
			"1.0"
		));
		StudentReport second = studentReportRepository.saveAndFlush(StudentReport.create(
			secondGeneration,
			classroom,
			learner,
			2,
			first,
			new BigDecimal("90.00"),
			"GREAT",
			"Second",
			Map.of("available", true),
			"test-model",
			"1.0"
		));
		reportResultRepository.save(ReportCriterionResult.create(
			second,
			"custom",
			1,
			new BigDecimal("90.00"),
			null,
			ReportCriterionStatus.ASSESSED,
			"Narrative",
			List.of("evidence-1")
		));
		evidenceRepository.saveAndFlush(ReportEvidenceSnapshot.create(
			secondGeneration,
			"evidence-1",
			"SESSION",
			"session:1",
			NOW,
			"Evidence",
			Map.of("completed", true),
			"source-hash"
		));
	}

	private User user(String email, String name, UserRole role) {
		return userRepository.save(User.create(email, "hash", name, role));
	}

	private Classroom classroom(User instructor, String name, String inviteCode) {
		return classroomRepository.saveAndFlush(Classroom.create(
			instructor,
			name,
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			inviteCode
		));
	}

	private void assertDeletedTablesAreEmpty() {
		for (String table : List.of(
			"exam_answers",
			"exam_submissions",
			"exam_questions",
			"exams",
			"report_criterion_results",
			"student_reports",
			"report_evidence_snapshots",
			"report_generations",
			"report_criteria",
			"classroom_resource",
			"classroom_notices",
			"classroom_week_materials",
			"classroom_weeks",
			"classroom_join_requests",
			"classroom_members",
			"classrooms"
		)) {
			assertThat(count(table))
				.as("%s should not retain classroom rows", table)
				.isZero();
		}
	}

	private long count(String table) {
		Long count = jdbcTemplate.queryForObject(
			"select count(*) from " + table,
			Long.class
		);
		return count == null ? 0 : count;
	}

}
