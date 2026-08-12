package io.edupilot.report;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ReportCriterionCatalog {
	private static final Logger log = LoggerFactory.getLogger(ReportCriterionCatalog.class);

	public static final String CATALOG_VERSION = "1.0";
	private static final int DEFAULT_MIN_EVIDENCE = 2;
	private static final BigDecimal DEFAULT_WEIGHT = new BigDecimal("1.00");

	private static final List<ReportCriterionDefinition> DEFAULT_CRITERIA = List.of(
		criterion(
			"concept_understanding",
			"개념 이해도",
			"핵심 개념을 정확히 이해하고 설명·판단하는 정도",
			ReportSourceType.QUIZ_SUBMISSION,
			ReportSourceType.QUIZ_ASSESSMENT,
			ReportSourceType.DIAGNOSIS,
			ReportSourceType.EXAM_SUBMISSION
		),
		criterion(
			"question_specificity",
			"질문 구체성",
			"학습 질문이 구체적인 대상과 이해 지점을 포함하는 정도",
			ReportSourceType.QA_QUESTION
		),
		criterion(
			"problem_solving",
			"문제 해결력",
			"문제 조건을 파악하고 적절한 해결 과정을 적용하는 정도",
			ReportSourceType.QUIZ_SUBMISSION,
			ReportSourceType.QUIZ_ASSESSMENT,
			ReportSourceType.DIAGNOSIS,
			ReportSourceType.EXAM_SUBMISSION
		),
		criterion(
			"application_transfer",
			"응용 및 전이력",
			"학습한 개념을 변형된 문제나 새로운 맥락에 적용하는 정도",
			ReportSourceType.QUIZ_ASSESSMENT,
			ReportSourceType.DIAGNOSIS,
			ReportSourceType.EXAM_SUBMISSION
		),
		criterion(
			"quiz_exam_accuracy",
			"퀴즈 및 시험 정확도",
			"통합 퀴즈와 별도 시험에서 확인된 정답 정확도",
			ReportSourceType.QUIZ_SUBMISSION,
			ReportSourceType.EXAM_SUBMISSION
		),
		criterion(
			"learning_persistence",
			"학습 지속성",
			"기간에 걸쳐 학습 활동과 학습 기억을 이어 가는 정도",
			ReportSourceType.SESSION,
			ReportSourceType.MEMORY
		),
		criterion(
			"error_reflection",
			"오답 성찰력",
			"오답 원인을 확인하고 보완 학습으로 연결하는 정도",
			ReportSourceType.QUIZ_ASSESSMENT,
			ReportSourceType.DIAGNOSIS,
			ReportSourceType.MEMORY
		),
		criterion(
			"class_participation",
			"수업 참여도",
			"세션·질문·퀴즈 활동에 참여한 관찰 가능한 정도",
			ReportSourceType.SESSION,
			ReportSourceType.QA_QUESTION,
			ReportSourceType.QUIZ_SUBMISSION
		),
		criterion(
			"growth_trend",
			"성장 흐름",
			"여러 시점의 평가와 학습 기억에서 확인되는 변화 흐름",
			ReportSourceType.QUIZ_SUBMISSION,
			ReportSourceType.QUIZ_ASSESSMENT,
			ReportSourceType.EXAM_SUBMISSION,
			ReportSourceType.MEMORY
		)
	);
	private static final Set<String> DEFAULT_KEYS = DEFAULT_CRITERIA.stream()
		.map(ReportCriterionDefinition::key)
		.map(ReportCriterionCatalog::normalizeKeyForComparison)
		.collect(Collectors.toUnmodifiableSet());

	private final ReportCriterionRepository criterionRepository;

	public ReportCriterionCatalog(ReportCriterionRepository criterionRepository) {
		this.criterionRepository = criterionRepository;
	}

	public List<ReportCriterionDefinition> effectiveCatalog(Long classroomId) {
		Map<String, ReportCriterionDefinition> catalog = new LinkedHashMap<>();
		DEFAULT_CRITERIA.forEach(criterion ->
			catalog.put(normalizeKeyForComparison(criterion.key()), criterion)
		);
		criterionRepository
			.findByClassroom_IdAndActiveTrueOrderByCriterionKeyAscVersionDesc(classroomId)
			.forEach(criterion -> {
				ReportCriterionDefinition custom = customDefinition(criterion);
				String normalizedKey = normalizeKeyForComparison(custom.key());
				if (catalog.putIfAbsent(normalizedKey, custom) != null) {
					log.atWarn()
						.addKeyValue("classroomId", classroomId)
						.addKeyValue("criterionKey", normalizedKey)
						.addKeyValue("retainedBuiltin", DEFAULT_KEYS.contains(normalizedKey))
						.log("Ignored duplicate custom report criterion key");
				}
			});
		return List.copyOf(catalog.values());
	}

	public List<ReportCriterionDefinition> defaultCriteria() {
		return DEFAULT_CRITERIA;
	}

	public boolean isDefaultKey(String criterionKey) {
		return DEFAULT_KEYS.contains(normalizeKeyForComparison(criterionKey));
	}

	static String normalizeKeyForComparison(String criterionKey) {
		return criterionKey.trim()
			.toLowerCase(Locale.ROOT)
			.replaceAll("[\\s-]+", "_");
	}

	private ReportCriterionDefinition customDefinition(ReportCriterion criterion) {
		EnumSet<ReportSourceType> allowedSources = EnumSet.noneOf(ReportSourceType.class);
		criterion.getAllowedSources().stream()
			.map(ReportSourceType::valueOf)
			.forEach(allowedSources::add);
		Object summary = criterion.getRubric().get("summary");
		return new ReportCriterionDefinition(
			criterion.getCriterionKey(),
			criterion.getName(),
			summary == null ? criterion.getDescription() : summary.toString(),
			allowedSources,
			criterion.getMinEvidence(),
			criterion.getWeight(),
			Integer.toString(criterion.getVersion())
		);
	}

	private static ReportCriterionDefinition criterion(
		String key,
		String name,
		String rubricSummary,
		ReportSourceType firstSource,
		ReportSourceType... otherSources
	) {
		EnumSet<ReportSourceType> sources = EnumSet.of(firstSource, otherSources);
		return new ReportCriterionDefinition(
			key,
			name,
			rubricSummary,
			sources,
			DEFAULT_MIN_EVIDENCE,
			DEFAULT_WEIGHT,
			CATALOG_VERSION
		);
	}
}
