package io.edupilot.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class ReportEvidenceSelector {

	private static final Set<ReportSourceType> ASSESSMENT_SOURCES = EnumSet.of(
		ReportSourceType.EXAM_SUBMISSION,
		ReportSourceType.QUIZ_SUBMISSION,
		ReportSourceType.QUIZ_ASSESSMENT,
		ReportSourceType.DIAGNOSIS
	);

	private static final Comparator<PreparedSource> SELECTION_ORDER =
		Comparator.comparing(
			(PreparedSource source) -> source.record().occurredAt(),
			Comparator.reverseOrder()
		).thenComparing(PreparedSource::evidenceId);

	public List<PreparedSource> deduplicate(
		List<ReportSnapshotInput.SourceRecord> records
	) {
		List<PreparedSource> prepared = records.stream()
			.map(record -> new PreparedSource(evidenceId(
				record.sourceType(),
				record.sourceRef()
			), record))
			.sorted(SELECTION_ORDER)
			.toList();
		Map<String, PreparedSource> distinct = new LinkedHashMap<>();
		prepared.forEach(source -> distinct.putIfAbsent(source.evidenceId(), source));
		return List.copyOf(distinct.values());
	}

	public List<ReportSnapshot.Evidence> select(
		List<PreparedSource> sources,
		int limit
	) {
		List<PreparedSource> assessment = sources.stream()
			.filter(source -> ASSESSMENT_SOURCES.contains(source.record().sourceType()))
			.sorted(SELECTION_ORDER)
			.toList();
		List<PreparedSource> remaining = sources.stream()
			.filter(source -> !ASSESSMENT_SOURCES.contains(source.record().sourceType()))
			.sorted(SELECTION_ORDER)
			.toList();

		List<PreparedSource> selected = new ArrayList<>(Math.min(limit, sources.size()));
		selected.addAll(assessment.subList(0, Math.min(limit, assessment.size())));
		int remainingSlots = limit - selected.size();
		if (remainingSlots > 0) {
			selected.addAll(remaining.subList(0, Math.min(remainingSlots, remaining.size())));
		}
		return selected.stream().map(this::toEvidence).toList();
	}

	public String evidenceId(ReportSourceType sourceType, String sourceRef) {
		String identity = sourceType.name() + ":" + sourceRef;
		try {
			return java.util.HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(
					identity.getBytes(StandardCharsets.UTF_8)
				)
			);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ReportSnapshot.Evidence toEvidence(PreparedSource source) {
		ReportSnapshotInput.SourceRecord record = source.record();
		return new ReportSnapshot.Evidence(
			source.evidenceId(),
			record.sourceType(),
			record.sourceRef(),
			record.occurredAt(),
			record.publicLabel(),
			record.minimalFact()
		);
	}

	public record PreparedSource(
		String evidenceId,
		ReportSnapshotInput.SourceRecord record
	) {
	}
}
