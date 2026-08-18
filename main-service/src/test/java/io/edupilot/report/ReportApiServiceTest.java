package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.report.dto.ReportCompletedResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReportApiServiceTest {

	@Mock private ClassroomService classroomService;
	@Mock private ClassroomMemberRepository memberRepository;
	@Mock private ReportGenerationService generationService;
	@Mock private ReportGenerationRepository generationRepository;
	@Mock private StudentReportRepository reportRepository;
	@Mock private ReportCriterionResultRepository resultRepository;
	@Mock private ReportEvidenceSnapshotRepository evidenceRepository;

	private ReportApiService service;

	@BeforeEach
	void setUp() {
		service = new ReportApiService(
			classroomService,
			memberRepository,
			generationService,
			generationRepository,
			reportRepository,
			resultRepository,
			evidenceRepository,
			new ReportGenerationProperties(
				Duration.ofMinutes(5),
				Duration.ofMinutes(10),
				5,
				new ReportGenerationProperties.Executor(1, 2, 50)
			),
			new ObjectMapper()
		);
	}

	@Test
	void listHidesNonMemberStudent() {
		when(memberRepository.existsByClassroom_IdAndUser_Id(30L, 40L))
			.thenReturn(false);

		assertThatThrownBy(() -> service.list(
			1L, UserRole.INSTRUCTOR, 30L, 40L
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.REPORT_NOT_FOUND)
			);
	}

	@Test
	void detailHidesReportFromNonManagingInstructor() {
		ReportGeneration generation = generation();
		when(generationRepository.findById(901L)).thenReturn(Optional.of(generation));
		doThrow(new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND))
			.when(classroomService)
			.requireStrictOwner(2L, UserRole.INSTRUCTOR, 30L);

		assertThatThrownBy(() -> service.detail(
			2L, UserRole.INSTRUCTOR, "901"
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLASSROOM_NOT_FOUND)
			);
	}

	@Test
	void detailMapsKnownEvidenceMetricsAndKeepsEvidenceFiltering() {
		ReportGeneration generation = generation();
		generation.complete();
		StudentReport report = StudentReport.create(
			generation,
			generation.classroom(),
			generation.student(),
			1,
			null,
			new BigDecimal("80.00"),
			"GOOD",
			null,
			Map.of(),
			"model",
			"1.0"
		);
		ReflectionTestUtils.setField(report, "id", 701L);
		ReflectionTestUtils.setField(report, "createdAt", Instant.EPOCH);
		ReportCriterionResult result = ReportCriterionResult.create(
			report,
			"criterion",
			1,
			new BigDecimal("80"),
			ReportTrend.FLAT,
			ReportCriterionStatus.ASSESSED,
			"narrative",
			List.of("ev-score", "ev-counts", "ev-memory")
		);
		List<ReportEvidenceSnapshot> snapshots = List.of(
			evidence(generation, "ev-score", "QUIZ_SUBMISSION", Map.of(
				"score", new BigDecimal("8.00"),
				"maxScore", new BigDecimal("10.0"),
				"normalizedScore", new BigDecimal("80.00"),
				"passed", true,
				"attemptNo", 2,
				"unknown", 999
			)),
			evidence(generation, "ev-counts", "QUIZ_ASSESSMENT", Map.of(
				"passed", false,
				"strengthCount", 3,
				"weaknessCount", 2,
				"misconceptionCount", 1,
				"focusConceptCount", 4
			)),
			evidence(generation, "ev-memory", "MEMORY", Map.of(
				"targetDifficulty", "NORMAL"
			)),
			evidence(generation, "ev-unreferenced", "EXAM_SUBMISSION", Map.of(
				"score", 100,
				"maxScore", 100
			))
		);
		when(generationRepository.findById(901L)).thenReturn(Optional.of(generation));
		when(memberRepository.existsByClassroom_IdAndUser_Id(30L, 40L))
			.thenReturn(true);
		when(reportRepository.findByGeneration_Id(901L)).thenReturn(Optional.of(report));
		when(resultRepository.findByReport_IdOrderByCriterionKey(701L))
			.thenReturn(List.of(result));
		when(evidenceRepository
			.findByGeneration_IdOrderByOccurredAtAscEvidenceIdAsc(901L))
			.thenReturn(snapshots);

		ReportCompletedResponse response = (ReportCompletedResponse)service.detail(
			1L, UserRole.INSTRUCTOR, "901"
		);

		assertThat(response.evidence()).extracting(
			ReportCompletedResponse.Evidence::evidenceId
		).containsExactly("ev-score", "ev-counts", "ev-memory");
		assertThat(response.evidence().get(0).metrics()).containsExactly(
			new ReportCompletedResponse.Metric("점수", "8점 / 10점"),
			new ReportCompletedResponse.Metric("환산 점수", "80점"),
			new ReportCompletedResponse.Metric("통과 여부", "통과"),
			new ReportCompletedResponse.Metric("시도 회차", "2회차")
		);
		assertThat(response.evidence().get(1).metrics()).containsExactly(
			new ReportCompletedResponse.Metric("통과 여부", "미통과"),
			new ReportCompletedResponse.Metric("강점 문항", "3개"),
			new ReportCompletedResponse.Metric("보완 문항", "2개"),
			new ReportCompletedResponse.Metric("오개념 문항", "1개"),
			new ReportCompletedResponse.Metric("집중 개념", "4개")
		);
		assertThat(response.evidence().get(2).metrics()).isEmpty();
		assertThat(response.evidence().get(0))
			.extracting(
				ReportCompletedResponse.Evidence::sourceType,
				ReportCompletedResponse.Evidence::publicLabel,
				ReportCompletedResponse.Evidence::occurredAt
			)
			.containsExactly("QUIZ_SUBMISSION", "label-ev-score", Instant.EPOCH);
	}

	private ReportEvidenceSnapshot evidence(
		ReportGeneration generation,
		String evidenceId,
		String sourceType,
		Map<String, Object> minimalFact
	) {
		return ReportEvidenceSnapshot.create(
			generation,
			evidenceId,
			sourceType,
			"source-" + evidenceId,
			Instant.EPOCH,
			"label-" + evidenceId,
			minimalFact,
			"hash-" + evidenceId
		);
	}

	private ReportGeneration generation() {
		User instructor = user(1L, "teacher@example.com", UserRole.INSTRUCTOR);
		User student = user(40L, "student@example.com", UserRole.LEARNER);
		Classroom classroom = Classroom.create(
			instructor,
			"강의실",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 1),
			ClassroomColor.BLUE,
			null,
			"INVITE"
		);
		ReflectionTestUtils.setField(classroom, "id", 30L);
		ReportGeneration generation = ReportGeneration.create(
			classroom,
			student,
			instructor,
			"request-1",
			ReportScopeType.FULL,
			null,
			ReportGenerationService.scopeHash(ReportScope.full()),
			"1.0"
		);
		ReflectionTestUtils.setField(generation, "id", 901L);
		return generation;
	}

	private User user(Long id, String email, UserRole role) {
		User user = User.create(email, "hash", role.name(), role);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
