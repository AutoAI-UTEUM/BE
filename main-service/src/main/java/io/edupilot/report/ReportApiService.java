package io.edupilot.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.report.dto.ReportAcceptedResponse;
import io.edupilot.report.dto.ReportCompletedResponse;
import io.edupilot.report.dto.ReportFailedResponse;
import io.edupilot.report.dto.ReportListResponse;
import io.edupilot.report.dto.ReportProgressResponse;
import io.edupilot.user.UserRole;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportApiService {
	private static final Map<String, MetricDefinition> EVIDENCE_METRIC_DEFINITIONS =
		evidenceMetricDefinitions();

	private static final List<ReportGenerationStatus> ACTIVE_STATUSES = List.of(
		ReportGenerationStatus.PENDING,
		ReportGenerationStatus.PROCESSING
	);

	private final ClassroomService classroomService;
	private final ClassroomMemberRepository memberRepository;
	private final ReportGenerationService generationService;
	private final ReportGenerationRepository generationRepository;
	private final StudentReportRepository reportRepository;
	private final ReportCriterionResultRepository resultRepository;
	private final ReportEvidenceSnapshotRepository evidenceRepository;
	private final ReportGenerationProperties properties;
	private final ObjectMapper objectMapper;

	public ReportApiService(
		ClassroomService classroomService,
		ClassroomMemberRepository memberRepository,
		ReportGenerationService generationService,
		ReportGenerationRepository generationRepository,
		StudentReportRepository reportRepository,
		ReportCriterionResultRepository resultRepository,
		ReportEvidenceSnapshotRepository evidenceRepository,
		ReportGenerationProperties properties,
		ObjectMapper objectMapper
	) {
		this.classroomService = classroomService;
		this.memberRepository = memberRepository;
		this.generationService = generationService;
		this.generationRepository = generationRepository;
		this.reportRepository = reportRepository;
		this.resultRepository = resultRepository;
		this.evidenceRepository = evidenceRepository;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public ReportAcceptedResponse create(
		Long instructorId,
		UserRole role,
		Long classroomId,
		Long studentId,
		ReportScope scope,
		String requestId
	) {
		classroomService.requireStrictOwner(instructorId, role, classroomId);
		ReportGenerationService.RequestResult result = generationService.request(
			instructorId, classroomId, studentId, scope, requestId
		);
		return new ReportAcceptedResponse(
			result.generationId().toString(),
			result.status(),
			properties.pollAfterSeconds()
		);
	}

	@Transactional(readOnly = true)
	public ReportListResponse list(
		Long instructorId,
		UserRole role,
		Long classroomId,
		Long studentId
	) {
		classroomService.requireStrictOwner(instructorId, role, classroomId);
		requireMember(classroomId, studentId);
		List<ReportListResponse.CompletedReportItem> items = reportRepository
			.findByClassroom_IdAndStudent_IdOrderByVersionDesc(classroomId, studentId)
			.stream()
			.map(report -> new ReportListResponse.CompletedReportItem(
				report.getGenerationId().toString(),
				report.getVersion(),
				report.getOverallScore(),
				report.getOverallStage(),
				report.getCreatedAt()
			))
			.toList();
		ReportListResponse.ActiveGeneration active = generationRepository
			.findFirstByClassroom_IdAndStudent_IdAndStatusInOrderByCreatedAtDesc(
				classroomId, studentId, ACTIVE_STATUSES
			)
			.map(generation -> new ReportListResponse.ActiveGeneration(
				generation.getId().toString(),
				generation.getStatus(),
				properties.pollAfterSeconds()
			))
			.orElse(null);
		return new ReportListResponse(items, active);
	}

	@Transactional(readOnly = true)
	public Object detail(Long instructorId, UserRole role, String reportId) {
		ReportGeneration generation = generationRepository.findById(parseId(reportId))
			.orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
		classroomService.requireStrictOwner(
			instructorId, role, generation.getClassroomId()
		);
		requireMember(generation.getClassroomId(), generation.getStudentId());
		return switch (generation.getStatus()) {
			case PENDING, PROCESSING -> new ReportProgressResponse(
				reportId,
				generation.getStatus(),
				properties.pollAfterSeconds()
			);
			case FAILED -> failed(generation);
			case COMPLETED -> completed(generation);
		};
	}

	private ReportFailedResponse failed(ReportGeneration generation) {
		Map<String, Object> input = generation.getGenerationInput();
		return new ReportFailedResponse(
			generation.getId().toString(),
			generation.getStatus(),
			generation.getFailureCode(),
			new ReportFailedResponse.Fallback(
				nestedMap(input, "metrics"),
				nestedMap(input, "dataQuality")
			)
		);
	}

	private ReportCompletedResponse completed(ReportGeneration generation) {
		StudentReport report = reportRepository.findByGeneration_Id(generation.getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
		List<ReportCriterionResult> results = resultRepository
			.findByReport_IdOrderByCriterionKey(report.getId());
		Set<String> evidenceIds = new LinkedHashSet<>();
		List<ReportCompletedResponse.CriterionResult> criteria = results.stream()
			.map(result -> {
				evidenceIds.addAll(result.getEvidenceIds());
				return new ReportCompletedResponse.CriterionResult(
					result.getCriterionKey(),
					result.getCriterionVersion(),
					result.getScore(),
					result.getTrend(),
					result.getStatus(),
					result.getNarrative(),
					result.getEvidenceIds()
				);
			})
			.toList();
		List<ReportCompletedResponse.Evidence> evidence = evidenceRepository
			.findByGeneration_IdOrderByOccurredAtAscEvidenceIdAsc(generation.getId())
			.stream()
			.filter(snapshot -> evidenceIds.contains(snapshot.getEvidenceId()))
			.map(snapshot -> new ReportCompletedResponse.Evidence(
				snapshot.getEvidenceId(),
				snapshot.getSourceType(),
				snapshot.getPublicLabel(),
				snapshot.getOccurredAt(),
				evidenceMetrics(snapshot.getMinimalFact())
			))
			.toList();
		return new ReportCompletedResponse(
			generation.getId().toString(),
			generation.getStatus(),
			report.getVersion(),
			report.getPreviousVersion(),
			report.getOverallScore(),
			report.getOverallStage(),
			summary(report.getSummary()),
			criteria,
			evidence,
			report.getCreatedAt()
		);
	}

	private List<ReportCompletedResponse.Metric> evidenceMetrics(
		Map<String, Object> minimalFact
	) {
		List<ReportCompletedResponse.Metric> metrics = new ArrayList<>();
		for (Map.Entry<String, MetricDefinition> entry :
			EVIDENCE_METRIC_DEFINITIONS.entrySet()) {
			MetricDefinition definition = entry.getValue();
			String value = definition.formatter().format(
				minimalFact.get(entry.getKey()),
				minimalFact
			);
			if (value != null) {
				metrics.add(new ReportCompletedResponse.Metric(
					definition.label(), value
				));
			}
		}
		return List.copyOf(metrics);
	}

	private static Map<String, MetricDefinition> evidenceMetricDefinitions() {
		Map<String, MetricDefinition> definitions = new LinkedHashMap<>();
		definitions.put("score", new MetricDefinition("점수", (value, facts) -> {
			BigDecimal score = decimal(value);
			BigDecimal maxScore = decimal(facts.get("maxScore"));
			if (score == null || maxScore == null) {
				return null;
			}
			return formatDecimal(score) + "점 / " + formatDecimal(maxScore) + "점";
		}));
		definitions.put("normalizedScore", new MetricDefinition(
			"환산 점수", decimalWithSuffix("점")
		));
		definitions.put("passed", new MetricDefinition(
			"통과 여부",
			(value, facts) -> value instanceof Boolean passed
				? passed ? "통과" : "미통과"
				: null
		));
		definitions.put("attemptNo", new MetricDefinition(
			"시도 회차", decimalWithSuffix("회차")
		));
		definitions.put("strengthCount", new MetricDefinition(
			"강점 문항", decimalWithSuffix("개")
		));
		definitions.put("weaknessCount", new MetricDefinition(
			"보완 문항", decimalWithSuffix("개")
		));
		definitions.put("misconceptionCount", new MetricDefinition(
			"오개념 문항", decimalWithSuffix("개")
		));
		definitions.put("focusConceptCount", new MetricDefinition(
			"집중 개념", decimalWithSuffix("개")
		));
		return Collections.unmodifiableMap(definitions);
	}

	private static MetricFormatter decimalWithSuffix(String suffix) {
		return (value, facts) -> {
			BigDecimal decimal = decimal(value);
			return decimal == null ? null : formatDecimal(decimal) + suffix;
		};
	}

	private static BigDecimal decimal(Object value) {
		if (!(value instanceof Number)) {
			return null;
		}
		try {
			return new BigDecimal(value.toString());
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static String formatDecimal(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private record MetricDefinition(
		String label,
		MetricFormatter formatter
	) {
	}

	@FunctionalInterface
	private interface MetricFormatter {
		String format(Object value, Map<String, Object> facts);
	}

	private Long parseId(String reportId) {
		try {
			return Long.valueOf(reportId);
		} catch (NumberFormatException exception) {
			throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
		}
	}

	private void requireMember(Long classroomId, Long studentId) {
		if (!memberRepository.existsByClassroom_IdAndUser_Id(
			classroomId, studentId
		)) {
			throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
		if (source == null || !(source.get(key) instanceof Map<?, ?> value)) {
			return Map.of();
		}
		return Map.copyOf((Map<String, Object>)value);
	}

	@SuppressWarnings("unchecked")
	private Object summary(String value) {
		if (value == null) {
			return null;
		}
		Map<String, Object> wrapper = objectMapper.readValue(value, Map.class);
		return wrapper.get("summary");
	}
}
