package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.ai.dto.ReportGenerateResponse;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReportAiGenerationServiceTest {

	@Mock private ReportGenerationRepository generationRepository;
	@Mock private ReportEvidenceSnapshotRepository evidenceRepository;
	@Mock private StudentReportRepository reportRepository;
	@Mock private ReportCriterionResultRepository resultRepository;
	@Mock private AiClient aiClient;

	private ObjectMapper objectMapper;
	private ReportAiGenerationService service;
	private User instructor;
	private User student;
	private Classroom classroom;
	private ReportGeneration generation;
	private ReportEvidenceSnapshot evidence;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		service = new ReportAiGenerationService(
			generationRepository,
			evidenceRepository,
			reportRepository,
			resultRepository,
			aiClient,
			objectMapper
		);
		instructor = User.create(
			"instructor@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		);
		student = User.create(
			"student@example.com", "hash", "Student", UserRole.LEARNER
		);
		classroom = Classroom.create(
			instructor,
			"Report classroom",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			"REPORTAI"
		);
		generation = ReportGeneration.create(
			classroom,
			student,
			instructor,
			"request-1",
			ReportScopeType.FULL,
			null,
			"scope-hash",
			"1.0"
		);
		generation.freezeSnapshot(
			"snapshot-hash",
			frozenInput(true),
			Instant.parse("2026-08-03T00:00:00Z")
		);
		evidence = ReportEvidenceSnapshot.create(
			generation,
			"evidence-1",
			ReportSourceType.QA_QUESTION.name(),
			"qa-message:1",
			Instant.parse("2026-08-03T00:00:00Z"),
			"구체적인 질문",
			Map.of("characterCount", 20),
			"source-hash"
		);
		when(generationRepository.findById(1L)).thenReturn(Optional.of(generation));
		when(evidenceRepository
			.findByGeneration_IdOrderByOccurredAtAscEvidenceIdAsc(1L))
			.thenReturn(List.of(evidence));
		when(reportRepository
			.findFirstByClassroom_IdAndStudent_IdOrderByVersionDesc(null, null))
			.thenReturn(Optional.empty());
	}

	@Test
	void rejectsUnknownCriterion() {
		when(aiClient.generateReport(org.mockito.ArgumentMatchers.any()))
			.thenReturn(response("unknown", 80, true, "evidence-1"));

		assertInvalid();
	}

	@Test
	void rejectsOutOfRangeScore() {
		when(aiClient.generateReport(org.mockito.ArgumentMatchers.any()))
			.thenReturn(response("question_specificity", 101, true, "evidence-1"));

		assertInvalid();
	}

	@Test
	void rejectsEvidenceOutsideGenerationSnapshot() {
		when(aiClient.generateReport(org.mockito.ArgumentMatchers.any()))
			.thenReturn(response("question_specificity", 80, true, "other-evidence"));

		assertInvalid();
	}

	@Test
	void rejectsAssessedResultWhenSpringEligibilityIsFalse() {
		generation = copyWithEligibility(false);
		when(generationRepository.findById(1L)).thenReturn(Optional.of(generation));
		when(aiClient.generateReport(org.mockito.ArgumentMatchers.any()))
			.thenReturn(response("question_specificity", 80, true, "evidence-1"));

		assertInvalid();
	}

	@Test
	void rejectsDuplicateEvidenceWithinCriterion() {
		ReportGenerateResponse response = response(
			"question_specificity", 80, true, "evidence-1"
		);
		ReportGenerateResponse.CriterionResult result = response
			.criterionResults().getFirst();
		response = new ReportGenerateResponse(
			response.schemaVersion(),
			response.reportId(),
			List.of(new ReportGenerateResponse.CriterionResult(
				result.criterionKey(),
				result.status(),
				result.score(),
				result.narrative(),
				List.of("evidence-1", "evidence-1")
			)),
			response.summary(),
			response.warnings(),
			response.usage(),
			null,
			null
		);
		when(aiClient.generateReport(org.mockito.ArgumentMatchers.any()))
			.thenReturn(response);

		assertInvalid();
	}

	private void assertInvalid() {
		assertThatThrownBy(() -> service.generate(1L))
			.isInstanceOf(AiClientException.class)
			.satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
				((AiClientException)exception).errorCode()
			).isEqualTo(io.edupilot.global.error.ErrorCode.AI_RESPONSE_INVALID));
	}

	private ReportGeneration copyWithEligibility(boolean eligible) {
		ReportGeneration copy = ReportGeneration.create(
			classroom,
			student,
			instructor,
			"request-2",
			ReportScopeType.FULL,
			null,
			"scope-hash",
			"1.0"
		);
		copy.freezeSnapshot(
			"snapshot-hash",
			frozenInput(eligible),
			Instant.parse("2026-08-03T00:00:00Z")
		);
		return copy;
	}

	private Map<String, Object> frozenInput(boolean eligible) {
		ReportCriterionDefinition criterion = new ReportCriterionDefinition(
			"question_specificity",
			"질문 구체성",
			"질문의 구체성을 평가",
			Set.of(ReportSourceType.QA_QUESTION),
			1,
			BigDecimal.ONE,
			"1.0"
		);
		ReportSnapshot.Metrics metrics = new ReportSnapshot.Metrics(
			new ReportSnapshot.Progress(1, 1, 100, true),
			scoreWindow(),
			scoreWindow(),
			new ReportSnapshot.Questions(1, 1),
			new ReportSnapshot.Activity(
				1,
				Instant.parse("2026-08-03T00:00:00Z")
			)
		);
		ReportSnapshot.DataQuality quality = new ReportSnapshot.DataQuality(
			"1.0",
			Set.of(ReportSourceType.QA_QUESTION),
			Set.of(),
			Map.of(
				"question_specificity",
				new ReportSnapshot.Eligibility(eligible, null, 1, 1)
			)
		);
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("criteria", convert(List.of(criterion)));
		input.put("metrics", convert(metrics));
		input.put("dataQuality", convert(quality));
		return input;
	}

	private ReportSnapshot.ScoreWindow scoreWindow() {
		ReportSnapshot.ScoreAggregate aggregate =
			new ReportSnapshot.ScoreAggregate(0, null);
		return new ReportSnapshot.ScoreWindow(aggregate, aggregate);
	}

	private Object convert(Object value) {
		return objectMapper.convertValue(
			objectMapper.valueToTree(value),
			Object.class
		);
	}

	private ReportGenerateResponse response(
		String criterionKey,
		Integer score,
		boolean assessed,
		String evidenceId
	) {
		ReportGenerateResponse.EvidencedStatement statement =
			new ReportGenerateResponse.EvidencedStatement(
				"근거가 있는 서술",
				List.of("evidence-1")
			);
		return new ReportGenerateResponse(
			"1.0",
			"1",
			List.of(new ReportGenerateResponse.CriterionResult(
				criterionKey,
				assessed
					? ReportGenerateResponse.CriterionStatus.ASSESSED
					: ReportGenerateResponse.CriterionStatus.INSUFFICIENT_DATA,
				score,
				"평가 서술",
				List.of(evidenceId)
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
			null,
			null
		);
	}
}
