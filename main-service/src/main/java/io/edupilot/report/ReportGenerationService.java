package io.edupilot.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReportGenerationService {

	private static final List<ReportGenerationStatus> ACTIVE_STATUSES = List.of(
		ReportGenerationStatus.PENDING,
		ReportGenerationStatus.PROCESSING
	);

	private final ReportGenerationRepository generationRepository;
	private final ReportSnapshotBuilder snapshotBuilder;
	private final ReportGenerationPersistenceService persistenceService;

	public ReportGenerationService(
		ReportGenerationRepository generationRepository,
		ReportSnapshotBuilder snapshotBuilder,
		ReportGenerationPersistenceService persistenceService
	) {
		this.generationRepository = generationRepository;
		this.snapshotBuilder = snapshotBuilder;
		this.persistenceService = persistenceService;
	}

	public RequestResult request(
		Long instructorId,
		Long classroomId,
		Long studentId,
		ReportScope scope,
		String requestId
	) {
		requireRequest(instructorId, classroomId, studentId, scope, requestId);
		snapshotBuilder.validateAccess(instructorId, classroomId, studentId, scope);
		String scopeHash = scopeHash(scope);

		return generationRepository
			.findByClassroom_IdAndStudent_IdAndRequestId(
				classroomId,
				studentId,
				requestId
			)
			.or(() -> active(classroomId, studentId, scopeHash))
			.map(generation -> result(generation, false))
			.orElseGet(() -> createOrReload(
				instructorId,
				classroomId,
				studentId,
				scope,
				requestId,
				scopeHash
			));
	}

	private RequestResult createOrReload(
		Long instructorId,
		Long classroomId,
		Long studentId,
		ReportScope scope,
		String requestId,
		String scopeHash
	) {
		try {
			return persistenceService.create(
				instructorId,
				classroomId,
				studentId,
				scope,
				requestId,
				scopeHash
			);
		} catch (DataIntegrityViolationException exception) {
			return generationRepository
				.findByClassroom_IdAndStudent_IdAndRequestId(
					classroomId,
					studentId,
					requestId
				)
				.or(() -> active(classroomId, studentId, scopeHash))
				.map(generation -> result(generation, false))
				.orElseThrow(() -> exception);
		}
	}

	private java.util.Optional<ReportGeneration> active(
		Long classroomId,
		Long studentId,
		String scopeHash
	) {
		return generationRepository
			.findFirstByClassroom_IdAndStudent_IdAndScopeHashAndStatusInOrderByCreatedAtAsc(
				classroomId,
				studentId,
				scopeHash,
				ACTIVE_STATUSES
			);
	}

	private void requireRequest(
		Long instructorId,
		Long classroomId,
		Long studentId,
		ReportScope scope,
		String requestId
	) {
		if (instructorId == null || classroomId == null || studentId == null
			|| scope == null || !StringUtils.hasText(requestId)) {
			throw new IllegalArgumentException("Report generation request is invalid");
		}
	}

	static String scopeHash(ReportScope scope) {
		String value = scope.type().name() + ":"
			+ (scope.weekNumber() == null ? "" : scope.weekNumber());
		try {
			return java.util.HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(
					value.getBytes(StandardCharsets.UTF_8)
				)
			);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	static RequestResult result(ReportGeneration generation, boolean created) {
		return new RequestResult(
			generation.getId(),
			generation.getStatus(),
			created
		);
	}

	public record RequestResult(
		Long generationId,
		ReportGenerationStatus status,
		boolean created
	) {
	}
}
