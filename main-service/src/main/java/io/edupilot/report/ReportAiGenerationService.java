package io.edupilot.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.ReportGenerateRequest;
import io.edupilot.ai.dto.ReportGenerateResponse;
import io.edupilot.global.error.ErrorCode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportAiGenerationService {

	private static final String SCHEMA_VERSION = "1.0";

	private final ReportGenerationRepository generationRepository;
	private final ReportEvidenceSnapshotRepository evidenceRepository;
	private final StudentReportRepository reportRepository;
	private final ReportCriterionResultRepository resultRepository;
	private final AiClient aiClient;
	private final ObjectMapper objectMapper;

	public ReportAiGenerationService(
		ReportGenerationRepository generationRepository,
		ReportEvidenceSnapshotRepository evidenceRepository,
		StudentReportRepository reportRepository,
		ReportCriterionResultRepository resultRepository,
		AiClient aiClient,
		ObjectMapper objectMapper
	) {
		this.generationRepository = generationRepository;
		this.evidenceRepository = evidenceRepository;
		this.reportRepository = reportRepository;
		this.resultRepository = resultRepository;
		this.aiClient = aiClient;
		this.objectMapper = objectMapper;
	}

	public GeneratedReport generate(Long generationId) {
		Prepared prepared = prepare(generationId);
		ReportGenerateResponse response = aiClient.generateReport(prepared.request());
		validate(prepared.request(), response);
		return new GeneratedReport(response);
	}

	private Prepared prepare(Long generationId) {
		ReportGeneration generation = generationRepository.findById(generationId)
			.orElseThrow(() -> invalid(null));
		FrozenInput input;
		try {
			input = objectMapper.convertValue(
				generation.getGenerationInput(),
				FrozenInput.class
			);
		} catch (RuntimeException exception) {
			throw invalid(exception);
		}
		List<ReportEvidenceSnapshot> storedEvidence = evidenceRepository
			.findByGeneration_IdOrderByOccurredAtAscEvidenceIdAsc(generationId);
		List<ReportGenerateRequest.Evidence> evidence = storedEvidence.stream()
			.map(this::evidence)
			.toList();
		List<ReportGenerateRequest.Criterion> criteria = input.criteria().stream()
			.map(this::criterion)
			.toList();
		if (criteria.isEmpty() || criteria.size() > 20 || evidence.size() > 200) {
			throw invalid(null);
		}
		ReportGenerateRequest request = new ReportGenerateRequest(
			SCHEMA_VERSION,
			generationId.toString(),
			generationId.toString(),
			scope(generation),
			metrics(input.metrics()),
			dataQuality(input.dataQuality()),
			criteria,
			evidence,
			previousReport(generation)
		);
		return new Prepared(request);
	}

	private ReportGenerateRequest.Scope scope(ReportGeneration generation) {
		String label = generation.getScopeType() == ReportScopeType.FULL
			? "전체 기간"
			: generation.getWeekNumber() + "주차";
		return new ReportGenerateRequest.Scope(label, null, null);
	}

	private List<ReportGenerateRequest.Metric> metrics(
		ReportSnapshot.Metrics metrics
	) {
		List<ReportGenerateRequest.Metric> values = new ArrayList<>();
		addMetric(values, "progressRate", "페이지 진도율",
			metrics.progress().progressRate(), ReportGenerateRequest.MetricWindow.CUMULATIVE);
		addMetric(values, "explainedPages", "설명 완료 페이지",
			metrics.progress().explainedPages(), ReportGenerateRequest.MetricWindow.CUMULATIVE);
		addMetric(values, "quizSubmissionCount", "퀴즈 제출 수",
			metrics.quiz().cumulative().submissionCount(),
			ReportGenerateRequest.MetricWindow.CUMULATIVE);
		addMetric(values, "quizAverageScore", "퀴즈 평균 점수",
			metrics.quiz().cumulative().averageNormalizedScore(),
			ReportGenerateRequest.MetricWindow.CUMULATIVE);
		addMetric(values, "quizRecentAverageScore", "최근 퀴즈 평균 점수",
			metrics.quiz().recent().averageNormalizedScore(),
			ReportGenerateRequest.MetricWindow.RECENT);
		addMetric(values, "examSubmissionCount", "시험 제출 수",
			metrics.exam().cumulative().submissionCount(),
			ReportGenerateRequest.MetricWindow.CUMULATIVE);
		addMetric(values, "examAverageScore", "시험 평균 점수",
			metrics.exam().cumulative().averageNormalizedScore(),
			ReportGenerateRequest.MetricWindow.CUMULATIVE);
		addMetric(values, "examRecentAverageScore", "최근 시험 평균 점수",
			metrics.exam().recent().averageNormalizedScore(),
			ReportGenerateRequest.MetricWindow.RECENT);
		addMetric(values, "questionCount", "질문 수",
			metrics.questions().cumulativeCount(),
			ReportGenerateRequest.MetricWindow.CUMULATIVE);
		addMetric(values, "recentQuestionCount", "최근 질문 수",
			metrics.questions().recentCount(), ReportGenerateRequest.MetricWindow.RECENT);
		addMetric(values, "sessionCount", "학습 세션 수",
			metrics.sessions().sessionCount(),
			ReportGenerateRequest.MetricWindow.CUMULATIVE);
		return List.copyOf(values);
	}

	private void addMetric(
		List<ReportGenerateRequest.Metric> metrics,
		String key,
		String label,
		Object value,
		ReportGenerateRequest.MetricWindow window
	) {
		metrics.add(new ReportGenerateRequest.Metric(
			key,
			label,
			value == null ? "" : value.toString(),
			window
		));
	}

	private ReportGenerateRequest.DataQuality dataQuality(
		ReportSnapshot.DataQuality quality
	) {
		List<ReportGenerateRequest.CriterionEligibility> eligibility =
			quality.criterionEligibility().entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.map(entry -> new ReportGenerateRequest.CriterionEligibility(
					entry.getKey(),
					entry.getValue().eligible(),
					entry.getValue().reason()
				))
				.toList();
		List<ReportGenerateRequest.EvidenceSourceType> available = mapSources(
			quality.availableSources()
		);
		EnumSet<ReportGenerateRequest.EvidenceSourceType> missing = EnumSet.allOf(
			ReportGenerateRequest.EvidenceSourceType.class
		);
		missing.removeAll(available);
		return new ReportGenerateRequest.DataQuality(
			quality.policyVersion(),
			available,
			List.copyOf(missing),
			eligibility
		);
	}

	private ReportGenerateRequest.Criterion criterion(FrozenCriterion criterion) {
		return new ReportGenerateRequest.Criterion(
			criterion.key(),
			criterion.name(),
			criterion.rubricSummary(),
			criterion.rubricSummary(),
			mapSources(criterion.allowedSources()),
			criterion.minEvidence(),
			criterionVersion(criterion.version())
		);
	}

	private ReportGenerateRequest.Evidence evidence(ReportEvidenceSnapshot evidence) {
		return new ReportGenerateRequest.Evidence(
			evidence.getEvidenceId(),
			mapSource(sourceType(evidence)),
			evidence.getOccurredAt().toString(),
			evidence.getPublicLabel(),
			objectMapper.writeValueAsString(evidence.getMinimalFact())
		);
	}

	private ReportGenerateRequest.PreviousReport previousReport(
		ReportGeneration generation
	) {
		StudentReport previous = reportRepository
			.findFirstByClassroom_IdAndStudent_IdOrderByVersionDesc(
				generation.getClassroomId(),
				generation.getStudentId()
			)
			.orElse(null);
		if (previous == null) {
			return null;
		}
		List<ReportGenerateRequest.PreviousCriterionResult> results = resultRepository
			.findByReport_IdOrderByCriterionKey(previous.getId()).stream()
			.map(result -> new ReportGenerateRequest.PreviousCriterionResult(
				result.getCriterionKey(),
				ReportGenerateResponse.CriterionStatus.valueOf(
					result.getStatus().name()
				),
				result.getScore() == null ? null : result.getScore().intValue()
			))
			.toList();
		return new ReportGenerateRequest.PreviousReport(previous.getVersion(), results);
	}

	private void validate(
		ReportGenerateRequest request,
		ReportGenerateResponse response
	) {
		if (response == null || !SCHEMA_VERSION.equals(response.schemaVersion())
			|| !request.reportId().equals(response.reportId())
			|| response.criterionResults() == null || response.summary() == null
			|| response.warnings() == null || response.usage() == null
			|| !StringUtils.hasText(response.usage().model())
			|| response.usage().inputTokens() == null
			|| response.usage().inputTokens() < 0
			|| response.usage().outputTokens() == null
			|| response.usage().outputTokens() < 0
			|| response.usage().reasoningTokens() != null
			&& response.usage().reasoningTokens() < 0) {
			throw invalid(null);
		}
		Map<String, ReportGenerateRequest.Criterion> criteria = new LinkedHashMap<>();
		request.criteria().forEach(criterion -> criteria.put(criterion.key(), criterion));
		Map<String, Boolean> eligibility = new LinkedHashMap<>();
		request.dataQuality().criterionEligibility().forEach(item ->
			eligibility.put(item.criterionKey(), item.eligible()));
		Set<String> evidence = request.evidence().stream()
			.map(ReportGenerateRequest.Evidence::evidenceId)
			.collect(java.util.stream.Collectors.toSet());
		Set<String> seenCriteria = new HashSet<>();
		for (ReportGenerateResponse.CriterionResult result
			: response.criterionResults()) {
			if (result == null || !criteria.containsKey(result.criterionKey())
				|| !seenCriteria.add(result.criterionKey())
				|| !StringUtils.hasText(result.narrative())) {
				throw invalid(null);
			}
			validateCriterionResult(result, evidence, eligibility);
		}
		if (!seenCriteria.equals(criteria.keySet())) {
			throw invalid(null);
		}
		validateSummary(response.summary(), evidence);
		for (ReportGenerateResponse.Warning warning : response.warnings()) {
			if (warning == null || warning.type() == null
				|| !StringUtils.hasText(warning.message())) {
				throw invalid(null);
			}
			validateEvidenceIds(warning.evidenceIds(), evidence, false);
		}
	}

	private void validateCriterionResult(
		ReportGenerateResponse.CriterionResult result,
		Set<String> evidence,
		Map<String, Boolean> eligibility
	) {
		if (result.status() == null || result.score() != null
			&& (result.score() < 0 || result.score() > 100)) {
			throw invalid(null);
		}
		if (result.status() == ReportGenerateResponse.CriterionStatus.ASSESSED) {
			if (result.score() == null
				|| !eligibility.getOrDefault(result.criterionKey(), false)) {
				throw invalid(null);
			}
			validateEvidenceIds(result.evidenceIds(), evidence, true);
		} else {
			if (result.score() != null) {
				throw invalid(null);
			}
			validateEvidenceIds(result.evidenceIds(), evidence, false);
		}
	}

	private void validateSummary(
		ReportGenerateResponse.Summary summary,
		Set<String> evidence
	) {
		if (!StringUtils.hasText(summary.overview())) {
			throw invalid(null);
		}
		validateStatements(summary.strengths(), evidence);
		validateStatements(summary.improvements(), evidence);
		validateStatements(summary.misconceptionCandidates(), evidence);
		validateStatements(summary.recommendedActions(), evidence);
	}

	private void validateStatements(
		List<ReportGenerateResponse.EvidencedStatement> statements,
		Set<String> evidence
	) {
			if (statements == null) {
				throw invalid(null);
			}
			for (ReportGenerateResponse.EvidencedStatement statement : statements) {
				if (statement == null || !StringUtils.hasText(statement.content())) {
					throw invalid(null);
				}
				validateEvidenceIds(statement.evidenceIds(), evidence, true);
			}
	}

	private void validateEvidenceIds(
		List<String> evidenceIds,
		Set<String> evidence,
		boolean required
	) {
		if (evidenceIds == null || required && evidenceIds.isEmpty()) {
			throw invalid(null);
		}
		Set<String> distinct = new HashSet<>();
		for (String evidenceId : evidenceIds) {
			if (!evidence.contains(evidenceId) || !distinct.add(evidenceId)) {
				throw invalid(null);
			}
		}
	}

	private List<ReportGenerateRequest.EvidenceSourceType> mapSources(
		Set<ReportSourceType> sources
	) {
		return sources.stream()
			.map(this::mapSource)
			.distinct()
			.sorted()
			.toList();
	}

	private ReportGenerateRequest.EvidenceSourceType mapSource(
		ReportSourceType source
	) {
		return switch (source) {
			case QA_QUESTION -> ReportGenerateRequest.EvidenceSourceType.QA;
			case QUIZ_SUBMISSION, QUIZ_ASSESSMENT ->
				ReportGenerateRequest.EvidenceSourceType.QUIZ;
			case DIAGNOSIS -> ReportGenerateRequest.EvidenceSourceType.DIAGNOSIS;
			case MEMORY -> ReportGenerateRequest.EvidenceSourceType.MEMORY;
			case EXAM_SUBMISSION -> ReportGenerateRequest.EvidenceSourceType.EXAM;
			case SESSION -> ReportGenerateRequest.EvidenceSourceType.SESSION;
		};
	}

	private ReportSourceType sourceType(ReportEvidenceSnapshot evidence) {
		try {
			return ReportSourceType.valueOf(evidence.getSourceType());
		} catch (IllegalArgumentException exception) {
			throw invalid(exception);
		}
	}

	private int criterionVersion(String version) {
		try {
			return new BigDecimal(version).intValueExact();
		} catch (RuntimeException exception) {
			throw invalid(exception);
		}
	}

	private AiClientException invalid(Throwable cause) {
		return new AiClientException(ErrorCode.AI_RESPONSE_INVALID, cause);
	}

	public record GeneratedReport(ReportGenerateResponse response) {
	}

	private record Prepared(ReportGenerateRequest request) {
	}

	private record FrozenInput(
		List<FrozenCriterion> criteria,
		ReportSnapshot.Metrics metrics,
		ReportSnapshot.DataQuality dataQuality
	) {
	}

	private record FrozenCriterion(
		String key,
		String name,
		String rubricSummary,
		Set<ReportSourceType> allowedSources,
		int minEvidence,
		BigDecimal weight,
		String version
	) {
	}
}
