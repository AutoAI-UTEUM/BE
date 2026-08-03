package io.edupilot.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomRepository;
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
	private final ObjectMapper objectMapper;

	public ReportGenerationPersistenceService(
		ReportGenerationRepository generationRepository,
		ReportEvidenceSnapshotRepository evidenceRepository,
		ClassroomRepository classroomRepository,
		UserRepository userRepository,
		ReportSnapshotBuilder snapshotBuilder,
		ReportCriterionCatalog criterionCatalog,
		ReportGenerationDispatcher dispatcher,
		ObjectMapper objectMapper
	) {
		this.generationRepository = generationRepository;
		this.evidenceRepository = evidenceRepository;
		this.classroomRepository = classroomRepository;
		this.userRepository = userRepository;
		this.snapshotBuilder = snapshotBuilder;
		this.criterionCatalog = criterionCatalog;
		this.dispatcher = dispatcher;
		this.objectMapper = objectMapper;
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
}
