package io.edupilot.report;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.ReportGenerateResponse;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserRepository;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportGenerationPersistenceService {
	private static final List<ReportGenerationStatus> ACTIVE_STATUSES = List.of(
		ReportGenerationStatus.PENDING,
		ReportGenerationStatus.PROCESSING
	);

	private final ReportGenerationRepository generationRepository;
	private final ReportEvidenceSnapshotRepository evidenceRepository;
	private final ClassroomRepository classroomRepository;
	private final UserRepository userRepository;
	private final ReportSnapshotBuilder snapshotBuilder;
	private final ReportCriterionCatalog criterionCatalog;
	private final ReportGenerationDispatcher dispatcher;
	private final StudentReportRepository reportRepository;
	private final ReportCriterionResultRepository resultRepository;
	private final ReportScoreCalculator scoreCalculator;
	private final ObjectMapper objectMapper;

	public ReportGenerationPersistenceService(
		ReportGenerationRepository generationRepository,
		ReportEvidenceSnapshotRepository evidenceRepository,
		ClassroomRepository classroomRepository,
		UserRepository userRepository,
		ReportSnapshotBuilder snapshotBuilder,
		ReportCriterionCatalog criterionCatalog,
		ReportGenerationDispatcher dispatcher,
		StudentReportRepository reportRepository,
		ReportCriterionResultRepository resultRepository,
		ReportScoreCalculator scoreCalculator,
		ObjectMapper objectMapper
	) {
		this.generationRepository = generationRepository;
		this.evidenceRepository = evidenceRepository;
		this.classroomRepository = classroomRepository;
		this.userRepository = userRepository;
		this.snapshotBuilder = snapshotBuilder;
		this.criterionCatalog = criterionCatalog;
		this.dispatcher = dispatcher;
		this.reportRepository = reportRepository;
		this.resultRepository = resultRepository;
		this.scoreCalculator = scoreCalculator;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public boolean claimGenerationLease(
		Long generationId,
		String leaseToken,
		Instant now,
		Instant leaseUntil
	) {
		return generationRepository.claimGenerationLease(
			generationId,
			leaseToken,
			now,
			leaseUntil
		) == 1;
	}

	@Transactional
	public boolean applyGeneratedReport(
		Long generationId,
		String leaseToken,
		ReportAiGenerationService.GeneratedReport generated
	) {
		ReportGeneration generation = generationRepository
			.findByIdForUpdate(generationId)
			.orElse(null);
		if (generation == null || !generation.hasGenerationLease(leaseToken)) {
			return false;
		}
		FrozenInput input = objectMapper.convertValue(
			generation.getGenerationInput(),
			FrozenInput.class
		);
		StudentReport previous = reportRepository
			.findFirstByClassroom_IdAndStudent_IdAndScopeKeyOrderByVersionDesc(
				generation.getClassroomId(),
				generation.getStudentId(),
				generation.getScopeKey()
			)
			.orElse(null);
		Map<String, ReportCriterionResult> previousResults = new HashMap<>();
		if (previous != null) {
			resultRepository.findByReport_IdOrderByCriterionKey(previous.getId())
				.forEach(result -> previousResults.put(
					result.getCriterionKey(),
					result
				));
		}
		Map<String, FrozenCriterion> criteria = new LinkedHashMap<>();
		input.criteria().forEach(criterion -> criteria.put(criterion.key(), criterion));
		ReportGenerateResponse response = generated.response();
		List<ReportScoreCalculator.CriterionScore> scores = response
			.criterionResults().stream()
			.map(result -> {
				FrozenCriterion criterion = criteria.get(result.criterionKey());
				return new ReportScoreCalculator.CriterionScore(
					result.criterionKey(),
					decimal(result.score()),
					status(result.status()),
					criterion.weight()
				);
			})
			.toList();
		ReportScoreCalculator.OverallResult overall = scoreCalculator.overall(scores);
		int version = previous == null ? 1 : previous.getVersion() + 1;
		StudentReport report;
		try {
			report = reportRepository.saveAndFlush(StudentReport.create(
				generation,
				generation.classroom(),
				generation.student(),
				version,
				previous,
				overall.score(),
				overall.stage(),
				summary(response),
				dataQuality(input.dataQuality()),
				response.usage().model(),
				"1.0"
			));
		} catch (DataIntegrityViolationException exception) {
			if (ReportVersionConflictException.matches(exception)) {
				throw new ReportVersionConflictException(
					generation.getScopeKey(), version, exception
				);
			}
			throw exception;
		}
		List<ReportCriterionResult> results = response.criterionResults().stream()
			.map(result -> {
				FrozenCriterion criterion = criteria.get(result.criterionKey());
				BigDecimal currentScore = decimal(result.score());
				ReportCriterionResult previousResult = previousResults.get(
					result.criterionKey()
				);
				return ReportCriterionResult.create(
					report,
					result.criterionKey(),
					criterionVersion(criterion.version()),
					currentScore,
					scoreCalculator.trend(
						previousResult == null ? null : previousResult.getScore(),
						currentScore
					),
					status(result.status()),
					result.narrative(),
					List.copyOf(result.evidenceIds())
				);
			})
			.toList();
		resultRepository.saveAll(results);
		resultRepository.flush();
		generation.complete(response.usage().model(), "1.0");
		generationRepository.flush();
		return true;
	}

	@Transactional
	public boolean failClaimedGeneration(
		Long generationId,
		String leaseToken,
		String failureCode
	) {
		ReportGeneration generation = generationRepository
			.findByIdForUpdate(generationId)
			.orElse(null);
		if (generation == null || !generation.hasGenerationLease(leaseToken)) {
			return false;
		}
		generation.fail(failureCode);
		generationRepository.flush();
		return true;
	}

	@Transactional
	public int failExpiredGenerations(
		Instant cutoff,
		Instant now,
		int batchSize
	) {
		List<Long> generationIds = generationRepository.findExpiredGenerationIds(
			cutoff,
			PageRequest.of(0, batchSize)
		);
		if (generationIds.isEmpty()) {
			return 0;
		}
		return generationRepository.failExpiredGenerations(
			generationIds,
			cutoff,
			ErrorCode.AI_SERVICE_TIMEOUT.code(),
			Instant.EPOCH,
			now
		);
	}

	@Transactional(readOnly = true)
	public List<Long> findRecoverableGenerations(
		Instant cutoff,
		Instant now,
		int batchSize
	) {
		return generationRepository.findRecoverableGenerations(
			cutoff,
			now,
			PageRequest.of(0, batchSize)
		).stream().map(ReportGeneration::getId).toList();
	}

	@Transactional
	public ReportGenerationService.RequestResult create(
		Long instructorId,
		Long classroomId,
		Long studentId,
		ReportScope scope,
		String requestId,
		String scopeHash
	) {
		Classroom classroom = classroomRepository.findByIdForUpdate(classroomId)
			.orElseThrow();
		ReportGeneration existing = generationRepository
			.findByClassroom_IdAndStudent_IdAndRequestId(
				classroomId,
				studentId,
				requestId
			)
			.orElse(null);
		if (existing != null) {
			return ReportGenerationService.result(existing, false);
		}
		existing = generationRepository
			.findFirstByClassroom_IdAndStudent_IdAndScopeHashAndStatusInOrderByCreatedAtAsc(
				classroomId,
				studentId,
				scopeHash,
				ACTIVE_STATUSES
			)
			.orElse(null);
		if (existing != null) {
			return ReportGenerationService.result(existing, false);
		}
		List<ReportCriterionDefinition> catalog =
			criterionCatalog.effectiveCatalog(classroomId);
		ReportSnapshot snapshot = snapshotBuilder.build(
			instructorId,
			classroomId,
			studentId,
			scope,
			catalog
		);
		ReportGeneration generation = ReportGeneration.create(
			classroom,
			userRepository.getReferenceById(studentId),
			userRepository.getReferenceById(instructorId),
			requestId,
			scope.type(),
			scope.weekNumber(),
			scopeHash,
			snapshot.dataQuality().policyVersion()
		);
		generation.freezeSnapshot(
			snapshot.snapshotHash(),
			generationInput(catalog, snapshot),
			snapshot.sourceDataAsOf()
		);
		generationRepository.saveAndFlush(generation);
		evidenceRepository.saveAll(snapshot.evidence().stream()
			.map(evidence -> ReportEvidenceSnapshot.create(
				generation,
				evidence.evidenceId(),
				evidence.sourceType().name(),
				evidence.sourceRef(),
				evidence.occurredAt(),
				evidence.publicLabel(),
				evidence.minimalFact(),
				sourceHash(snapshot.snapshotHash(), evidence.evidenceId())
			))
			.toList());
		dispatcher.dispatchAfterCommit(generation.getId());
		return ReportGenerationService.result(generation, true);
	}

	private Map<String, Object> generationInput(
		List<ReportCriterionDefinition> catalog,
		ReportSnapshot snapshot
	) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("criteria", convert(catalog));
		input.put("metrics", convert(snapshot.metrics()));
		input.put("dataQuality", convert(snapshot.dataQuality()));
		return input;
	}

	@SuppressWarnings("unchecked")
	private <T> T convert(Object value) {
		return (T)objectMapper.convertValue(
			objectMapper.valueToTree(value),
			Object.class
		);
	}

	private String sourceHash(String snapshotHash, String evidenceId) {
		try {
			return java.util.HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(
					(snapshotHash + ":" + evidenceId)
						.getBytes(StandardCharsets.UTF_8)
				)
			);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private String summary(ReportGenerateResponse response) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("summary", response.summary());
		value.put("warnings", response.warnings());
		return objectMapper.writeValueAsString(value);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> dataQuality(ReportSnapshot.DataQuality quality) {
		return objectMapper.convertValue(
			objectMapper.valueToTree(quality),
			Map.class
		);
	}

	private BigDecimal decimal(Integer score) {
		return score == null ? null : BigDecimal.valueOf(score);
	}

	private ReportCriterionStatus status(
		ReportGenerateResponse.CriterionStatus status
	) {
		return ReportCriterionStatus.valueOf(status.name());
	}

	private int criterionVersion(String version) {
		return new BigDecimal(version).intValueExact();
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
		java.util.Set<ReportSourceType> allowedSources,
		int minEvidence,
		BigDecimal weight,
		String version
	) {
	}
}
