package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.ReportGenerateRequest;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.ai.dto.ReportGenerateResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;

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
	@Autowired private ReportGenerationPersistenceService persistenceService;
	@Autowired private ReportAiGenerationService aiGenerationService;
	@Autowired private EntityManager entityManager;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private ObjectMapper objectMapper;
	@MockitoBean private AiClient aiClient;

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

	@Test
	void lateWorkerCannotApplyAfterLeaseIsReclaimed() {
		ReportGeneration generation = generation(student, "late-worker");
		Instant firstClaim = Instant.now();
		assertThat(persistenceService.claimGenerationLease(
			generation.getId(),
			"first-token",
			firstClaim,
			firstClaim.plusSeconds(300)
		)).isTrue();
		assertThat(persistenceService.claimGenerationLease(
			generation.getId(),
			"second-token",
			firstClaim.plusSeconds(301),
			firstClaim.plusSeconds(601)
		)).isTrue();

		boolean applied = persistenceService.applyGeneratedReport(
			generation.getId(),
			"first-token",
			new ReportAiGenerationService.GeneratedReport(validResponse(80))
		);

		assertThat(applied).isFalse();
		assertThat(reportRepository.count()).isZero();
		assertThat(resultRepository.count()).isZero();
	}

	@Test
	void absoluteCutoffFailsGenerationEvenWithActiveLease() {
		ReportGeneration generation = generation(student, "absolute-cutoff");
		Instant claimedAt = Instant.now();
		assertThat(persistenceService.claimGenerationLease(
			generation.getId(),
			"active-token",
			claimedAt,
			claimedAt.plusSeconds(3600)
		)).isTrue();

		assertThat(persistenceService.failExpiredGenerations(
			claimedAt.plusSeconds(1),
			claimedAt.plusSeconds(2),
			100
		)).isEqualTo(1);

		ReportGeneration failed = generationRepository.findById(generation.getId())
			.orElseThrow();
		assertThat(failed.getStatus()).isEqualTo(ReportGenerationStatus.FAILED);
		assertThat(failed.getFailureCode()).isEqualTo("AI_SERVICE_TIMEOUT");
		assertThat(reportRepository.count()).isZero();
	}

	@Test
	void storesNewVersionPreviousTrendAndSpringOverall() {
		ReportGeneration first = frozenGeneration("versioned-1");
		assertThat(persistenceService.claimGenerationLease(
			first.getId(), "token-1", Instant.now(), Instant.now().plusSeconds(300)
		)).isTrue();
		assertThat(persistenceService.applyGeneratedReport(
			first.getId(),
			"token-1",
			new ReportAiGenerationService.GeneratedReport(validResponse(80))
		)).isTrue();

		ReportGeneration second = frozenGeneration("versioned-2");
		assertThat(persistenceService.claimGenerationLease(
			second.getId(), "token-2", Instant.now(), Instant.now().plusSeconds(300)
		)).isTrue();
		assertThat(persistenceService.applyGeneratedReport(
			second.getId(),
			"token-2",
			new ReportAiGenerationService.GeneratedReport(validResponse(90))
		)).isTrue();

		List<StudentReport> reports = reportRepository
			.findByClassroom_IdAndStudent_IdOrderByVersionDesc(
				classroom.getId(), student.getId()
			);
		assertThat(reports).extracting(StudentReport::getVersion)
			.containsExactly(2, 1);
		assertThat(reports.getFirst().getPreviousReportId())
			.isEqualTo(reports.get(1).getId());
		assertThat(reports.getFirst().getOverallScore())
			.isEqualByComparingTo("90.00");
		assertThat(reports.getFirst().getOverallStage()).isEqualTo("우수");
		assertThat(reports.get(1).getOverallScore()).isEqualByComparingTo("80.00");
		List<ReportCriterionResult> latestResults = resultRepository
			.findByReport_IdOrderByCriterionKey(reports.getFirst().getId());
		assertThat(latestResults).singleElement().satisfies(result -> {
			assertThat(result.getTrend()).isEqualTo(ReportTrend.UP);
			assertThat(result.getScore()).isEqualByComparingTo("90.00");
		});
	}

	@Test
	void versionsPreviousReportsAndTrendsWithinEachScopeChain() {
		apply(frozenGeneration("scope-full-1", ReportScopeType.FULL, null), 60);
		apply(frozenGeneration("scope-week-3-1", ReportScopeType.WEEK, 3), 20);
		apply(frozenGeneration("scope-week-5-1", ReportScopeType.WEEK, 5), 40);
		apply(frozenGeneration("scope-full-2", ReportScopeType.FULL, null), 70);
		apply(frozenGeneration("scope-week-3-2", ReportScopeType.WEEK, 3), 10);

		StudentReport full = reportRepository
			.findFirstByClassroom_IdAndStudent_IdAndScopeKeyOrderByVersionDesc(
				classroom.getId(), student.getId(), "FULL"
			)
			.orElseThrow();
		StudentReport weekThree = reportRepository
			.findFirstByClassroom_IdAndStudent_IdAndScopeKeyOrderByVersionDesc(
				classroom.getId(), student.getId(), "WEEK:3"
			)
			.orElseThrow();
		StudentReport weekFive = reportRepository
			.findFirstByClassroom_IdAndStudent_IdAndScopeKeyOrderByVersionDesc(
				classroom.getId(), student.getId(), "WEEK:5"
			)
			.orElseThrow();

		assertThat(full.getVersion()).isEqualTo(2);
		assertThat(full.getPreviousVersion()).isEqualTo(1);
		assertThat(trend(full)).isEqualTo(ReportTrend.UP);
		assertThat(weekThree.getVersion()).isEqualTo(2);
		assertThat(weekThree.getPreviousVersion()).isEqualTo(1);
		assertThat(trend(weekThree)).isEqualTo(ReportTrend.DOWN);
		assertThat(weekFive.getVersion()).isEqualTo(1);
		assertThat(weekFive.getPreviousVersion()).isNull();
		assertThat(trend(weekFive)).isNull();
	}

	@Test
	void sessionOnlyEvidenceCanBeAssessedAndStored() {
		ReportGeneration generation = sessionFrozenGeneration("session-assessed");
		evidenceRepository.saveAllAndFlush(List.of(
			evidence(generation, "session-1", ReportSourceType.SESSION),
			evidence(generation, "session-2", ReportSourceType.SESSION)
		));
		when(aiClient.generateReport(org.mockito.ArgumentMatchers.any()))
			.thenAnswer(invocation -> {
				ReportGenerateRequest request = invocation.getArgument(0);
				assertThat(request.evidence())
					.extracting(ReportGenerateRequest.Evidence::sourceType)
					.containsExactly(
						ReportGenerateRequest.EvidenceSourceType.SESSION,
						ReportGenerateRequest.EvidenceSourceType.SESSION
					);
				assertThat(request.dataQuality().availableSources())
					.containsExactly(
						ReportGenerateRequest.EvidenceSourceType.SESSION
					);
				assertThat(request.dataQuality().missingSources())
					.doesNotContain(
						ReportGenerateRequest.EvidenceSourceType.SESSION
					);
				return sessionResponse(request.reportId());
			});
		String leaseToken = "session-token";
		Instant claimedAt = Instant.now();
		assertThat(persistenceService.claimGenerationLease(
			generation.getId(),
			leaseToken,
			claimedAt,
			claimedAt.plusSeconds(300)
		)).isTrue();

		ReportAiGenerationService.GeneratedReport generated =
			aiGenerationService.generate(generation.getId());

		assertThat(persistenceService.applyGeneratedReport(
			generation.getId(),
			leaseToken,
			generated
		)).isTrue();
		StudentReport savedReport = reportRepository
			.findFirstByClassroom_IdAndStudent_IdAndScopeKeyOrderByVersionDesc(
				classroom.getId(), student.getId(), "FULL"
			)
			.orElseThrow();
		assertThat(resultRepository
			.findByReport_IdOrderByCriterionKey(savedReport.getId()))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.getCriterionKey())
					.isEqualTo("learning_persistence");
				assertThat(result.getStatus())
					.isEqualTo(ReportCriterionStatus.ASSESSED);
				assertThat(result.getEvidenceIds())
					.containsExactly("session-1", "session-2");
			});
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

	private ReportGeneration frozenGeneration(String requestId) {
		return frozenGeneration(requestId, ReportScopeType.FULL, null);
	}

	private ReportGeneration frozenGeneration(
		String requestId,
		ReportScopeType scopeType,
		Integer weekNumber
	) {
		ReportGeneration generation = ReportGeneration.create(
			classroom,
			student,
			instructor,
			requestId,
			scopeType,
			weekNumber,
			"scope-hash-" + requestId,
			"1.0"
		);
		generation.freezeSnapshot(
			"snapshot-hash-" + requestId,
			generationInput(),
			Instant.now()
		);
		generationRepository.saveAndFlush(generation);
		evidenceRepository.saveAndFlush(evidence(generation, "evidence-1"));
		return generation;
	}

	private void apply(ReportGeneration generation, int score) {
		String token = "token-" + generation.getRequestId();
		Instant now = Instant.now();
		assertThat(persistenceService.claimGenerationLease(
			generation.getId(), token, now, now.plusSeconds(300)
		)).isTrue();
		assertThat(persistenceService.applyGeneratedReport(
			generation.getId(),
			token,
			new ReportAiGenerationService.GeneratedReport(validResponse(score))
		)).isTrue();
	}

	private ReportTrend trend(StudentReport report) {
		return resultRepository.findByReport_IdOrderByCriterionKey(report.getId())
			.getFirst()
			.getTrend();
	}

	private ReportGeneration sessionFrozenGeneration(String requestId) {
		ReportGeneration generation = ReportGeneration.create(
			classroom,
			student,
			instructor,
			requestId,
			ReportScopeType.FULL,
			null,
			"scope-hash-" + requestId,
			"1.0"
		);
		generation.freezeSnapshot(
			"snapshot-hash-" + requestId,
			sessionGenerationInput(),
			Instant.now()
		);
		return generationRepository.saveAndFlush(generation);
	}

	private Map<String, Object> generationInput() {
		ReportCriterionDefinition criterion = new ReportCriterionDefinition(
			"question_specificity",
			"질문 구체성",
			"질문의 구체성을 평가",
			Set.of(ReportSourceType.QA_QUESTION),
			1,
			BigDecimal.ONE,
			"1.0"
		);
		ReportSnapshot.ScoreAggregate aggregate =
			new ReportSnapshot.ScoreAggregate(0, null);
		ReportSnapshot.ScoreWindow window =
			new ReportSnapshot.ScoreWindow(aggregate, aggregate);
		ReportSnapshot.Metrics metrics = new ReportSnapshot.Metrics(
			new ReportSnapshot.Progress(1, 1, 100, true),
			window,
			window,
			new ReportSnapshot.Questions(1, 1),
			new ReportSnapshot.Activity(1, Instant.now())
		);
		ReportSnapshot.DataQuality quality = new ReportSnapshot.DataQuality(
			"1.0",
			Set.of(ReportSourceType.QA_QUESTION),
			Set.of(),
			Map.of(
				"question_specificity",
				new ReportSnapshot.Eligibility(true, null, 1, 1)
			)
		);
		Map<String, Object> input = new java.util.LinkedHashMap<>();
		input.put("criteria", convert(List.of(criterion)));
		input.put("metrics", convert(metrics));
		input.put("dataQuality", convert(quality));
		return input;
	}

	private Map<String, Object> sessionGenerationInput() {
		ReportCriterionDefinition criterion = new ReportCriterionDefinition(
			"learning_persistence",
			"학습 지속성",
			"기간에 걸쳐 학습 활동을 이어 가는 정도",
			Set.of(ReportSourceType.SESSION),
			2,
			BigDecimal.ONE,
			"1.0"
		);
		ReportSnapshot.ScoreAggregate aggregate =
			new ReportSnapshot.ScoreAggregate(0, null);
		ReportSnapshot.ScoreWindow window =
			new ReportSnapshot.ScoreWindow(aggregate, aggregate);
		ReportSnapshot.Metrics metrics = new ReportSnapshot.Metrics(
			new ReportSnapshot.Progress(1, 1, 100, true),
			window,
			window,
			new ReportSnapshot.Questions(0, 0),
			new ReportSnapshot.Activity(2, Instant.now())
		);
		ReportSnapshot.DataQuality quality = new ReportSnapshot.DataQuality(
			"1.0",
			Set.of(ReportSourceType.SESSION),
			EnumSet.complementOf(EnumSet.of(ReportSourceType.SESSION)),
			Map.of(
				"learning_persistence",
				new ReportSnapshot.Eligibility(true, null, 2, 2)
			)
		);
		Map<String, Object> input = new java.util.LinkedHashMap<>();
		input.put("criteria", convert(List.of(criterion)));
		input.put("metrics", convert(metrics));
		input.put("dataQuality", convert(quality));
		return input;
	}

	private Object convert(Object value) {
		return objectMapper.convertValue(
			objectMapper.valueToTree(value),
			Object.class
		);
	}

	private ReportGenerateResponse validResponse(int score) {
		ReportGenerateResponse.EvidencedStatement statement =
			new ReportGenerateResponse.EvidencedStatement(
				"근거가 있는 서술",
				List.of("evidence-1")
			);
		return new ReportGenerateResponse(
			"1.0",
			"unused-by-persistence",
			List.of(new ReportGenerateResponse.CriterionResult(
				"question_specificity",
				ReportGenerateResponse.CriterionStatus.ASSESSED,
				score,
				"평가 서술",
				List.of("evidence-1")
			)),
			new ReportGenerateResponse.Summary(
				"요약",
				List.of(statement),
				List.of(statement),
				List.of(),
				List.of(statement)
			),
			List.of(),
			new AiUsage("test-model", 10L, 20L, null),
			new BigDecimal("1.00"),
			"보완 필요"
		);
	}

	private ReportGenerateResponse sessionResponse(String reportId) {
		ReportGenerateResponse.EvidencedStatement statement =
			new ReportGenerateResponse.EvidencedStatement(
				"세션 활동이 지속됨",
				List.of("session-1", "session-2")
			);
		return new ReportGenerateResponse(
			"1.0",
			reportId,
			List.of(new ReportGenerateResponse.CriterionResult(
				"learning_persistence",
				ReportGenerateResponse.CriterionStatus.ASSESSED,
				80,
				"세션 활동 근거로 학습 지속성을 평가함",
				List.of("session-1", "session-2")
			)),
			new ReportGenerateResponse.Summary(
				"세션 활동 기반 요약",
				List.of(statement),
				List.of(statement),
				List.of(),
				List.of(statement)
			),
			List.of(),
			new AiUsage("test-model", 10L, 20L, null),
			null,
			null
		);
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

	private ReportEvidenceSnapshot evidence(
		ReportGeneration generation,
		String evidenceId,
		ReportSourceType sourceType
	) {
		return ReportEvidenceSnapshot.create(
			generation,
			evidenceId,
			sourceType.name(),
			sourceType.name().toLowerCase() + ":" + evidenceId,
			Instant.parse("2026-08-03T00:00:00Z"),
			"학습 세션 완료",
			Map.of("completed", true),
			"source-hash-" + evidenceId
		);
	}
}
