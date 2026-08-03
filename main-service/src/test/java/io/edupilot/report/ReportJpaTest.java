package io.edupilot.report;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:report-jpa;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/report-jpa"
	}
)
@ActiveProfiles("jpa-context")
@Transactional
class ReportJpaTest {

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ReportGenerationRepository generationRepository;
	@Autowired private StudentReportRepository reportRepository;
	@Autowired private ReportCriterionResultRepository resultRepository;
	@Autowired private ReportEvidenceSnapshotRepository evidenceRepository;
	@Autowired private EntityManager entityManager;
	@Autowired private JdbcTemplate jdbcTemplate;

	private User instructor;
	private User student;
	private User otherStudent;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		instructor = userRepository.save(User.create(
			"report-instructor@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		));
		student = userRepository.save(User.create(
			"report-student@example.com", "hash", "Student", UserRole.LEARNER
		));
		otherStudent = userRepository.save(User.create(
			"report-other@example.com", "hash", "Other", UserRole.LEARNER
		));
		classroom = classroomRepository.save(Classroom.create(
			instructor,
			"Report classroom",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			"REPORTTEST"
		));
	}

	@Test
	void rejectsDuplicateStudentReportVersion() {
		ReportGeneration firstGeneration = generation(student, "version-1");
		ReportGeneration secondGeneration = generation(student, "version-2");
		reportRepository.saveAndFlush(report(firstGeneration, student, 1, null));

		assertThatThrownBy(() ->
			reportRepository.saveAndFlush(report(secondGeneration, student, 1, null))
		).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsDuplicateGenerationRequest() {
		generation(student, "same-request");

		assertThatThrownBy(() -> generation(student, "same-request"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsDuplicateEvidenceIdWithinGeneration() {
		ReportGeneration generation = generation(student, "evidence-generation");
		evidenceRepository.saveAndFlush(evidence(generation, "evidence-1"));

		assertThatThrownBy(() ->
			evidenceRepository.saveAndFlush(evidence(generation, "evidence-1"))
		).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsTwoReportsForOneGeneration() {
		ReportGeneration generation = generation(student, "single-report-generation");
		reportRepository.saveAndFlush(report(generation, student, 1, null));

		assertThatThrownBy(() ->
			reportRepository.saveAndFlush(report(generation, student, 2, null))
		).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void savesAndReadsPreviousReportSelfReference() {
		StudentReport previous = reportRepository.saveAndFlush(report(
			generation(student, "previous-1"), student, 1, null
		));
		StudentReport current = reportRepository.saveAndFlush(report(
			generation(student, "previous-2"), student, 2, previous
		));

		entityManager.clear();

		assertThat(reportRepository.findById(current.getId()).orElseThrow()
			.getPreviousReportId()).isEqualTo(previous.getId());
	}

	@Test
	void rejectsCriterionScoreAboveOneHundred() {
		StudentReport report = reportRepository.saveAndFlush(report(
			generation(student, "score-check"), student, 1, null
		));
		ReportCriterionResult result = ReportCriterionResult.create(
			report,
			"engagement",
			1,
			new BigDecimal("101.00"),
			null,
			ReportCriterionStatus.ASSESSED,
			"Narrative",
			List.of("evidence-1")
		);

		assertThatThrownBy(() -> resultRepository.saveAndFlush(result))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsAssessedCriterionWithoutScore() {
		StudentReport report = reportRepository.saveAndFlush(report(
			generation(student, "assessed-check"), student, 1, null
		));
		ReportCriterionResult result = ReportCriterionResult.create(
			report,
			"engagement",
			1,
			null,
			null,
			ReportCriterionStatus.ASSESSED,
			"Narrative",
			List.of("evidence-1")
		);

		assertThatThrownBy(() -> resultRepository.saveAndFlush(result))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsUnknownCriterionStatus() {
		StudentReport report = reportRepository.saveAndFlush(report(
			generation(student, "status-check"), student, 1, null
		));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"""
				INSERT INTO report_criterion_results (
				    report_id, criterion_key, criterion_version, score, trend,
				    status, narrative, evidence_ids_json, created_at, updated_at
				) VALUES (?, 'engagement', 1, NULL, NULL, 'UNKNOWN', NULL,
				    '[]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""",
			report.getId()
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void derivedStudentQueryDoesNotReturnAnotherStudentsReport() {
		StudentReport own = reportRepository.saveAndFlush(report(
			generation(student, "isolation-own"), student, 1, null
		));
		reportRepository.saveAndFlush(report(
			generation(otherStudent, "isolation-other"), otherStudent, 1, null
		));

		List<StudentReport> found = reportRepository
			.findByClassroom_IdAndStudent_IdOrderByVersionDesc(
				classroom.getId(), student.getId()
			);

		assertThat(found).extracting(StudentReport::getId).containsExactly(own.getId());
		assertThat(found).allSatisfy(saved ->
			assertThat(saved.getStudentId()).isEqualTo(student.getId())
		);
	}

	private ReportGeneration generation(User targetStudent, String requestId) {
		return generationRepository.saveAndFlush(ReportGeneration.create(
			classroom,
			targetStudent,
			instructor,
			requestId,
			ReportScopeType.FULL,
			null,
			"scope-hash-" + requestId,
			"1.0"
		));
	}

	private StudentReport report(
		ReportGeneration generation,
		User targetStudent,
		int version,
		StudentReport previous
	) {
		return StudentReport.create(
			generation,
			classroom,
			targetStudent,
			version,
			previous,
			new BigDecimal("80.00"),
			"양호",
			"Summary",
			Map.of("available", true),
			"test-model",
			"1.0"
		);
	}

	private ReportEvidenceSnapshot evidence(
		ReportGeneration generation,
		String evidenceId
	) {
		return ReportEvidenceSnapshot.create(
			generation,
			evidenceId,
			"SESSION_PAGE",
			"session:1:page:1",
			Instant.parse("2026-08-03T00:00:00Z"),
			"1페이지 설명 완료",
			Map.of("pageNumber", 1),
			"source-hash"
		);
	}
}
