package io.edupilot.report;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class ReportSnapshotCalculator implements ReportSnapshotComputer {

	private final ReportProperties properties;
	private final ReportEvidenceSelector evidenceSelector;
	private final ReportScoreCalculator scoreCalculator;
	private final ReportSufficiencyCalculator sufficiencyCalculator;
	private final ReportSnapshotHasher hasher;

	public ReportSnapshotCalculator(
		ReportProperties properties,
		ReportEvidenceSelector evidenceSelector,
		ReportScoreCalculator scoreCalculator,
		ReportSufficiencyCalculator sufficiencyCalculator,
		ReportSnapshotHasher hasher
	) {
		this.properties = properties;
		this.evidenceSelector = evidenceSelector;
		this.scoreCalculator = scoreCalculator;
		this.sufficiencyCalculator = sufficiencyCalculator;
		this.hasher = hasher;
	}

	@Override
	public ReportSnapshot compute(ReportSnapshotInput input) {
		List<ReportEvidenceSelector.PreparedSource> distinct =
			evidenceSelector.deduplicate(input.sources());
		List<ReportSnapshotInput.SourceRecord> sources = distinct.stream()
			.map(ReportEvidenceSelector.PreparedSource::record)
			.toList();
		List<ReportSnapshot.Evidence> evidence = evidenceSelector.select(
			distinct,
			properties.evidenceLimit()
		);
		Instant recentSince = input.sourceDataAsOf().minus(properties.recentWindow());

		ReportSnapshot.Metrics metrics = new ReportSnapshot.Metrics(
			progress(input.progress()),
			scoreCalculator.aggregate(sourcesOf(
				sources,
				ReportSourceType.QUIZ_SUBMISSION
			), recentSince),
			scoreCalculator.aggregate(sourcesOf(
				sources,
				ReportSourceType.EXAM_SUBMISSION
			), recentSince),
			questions(sources, recentSince),
			activity(sources)
		);
		ReportSnapshot.DataQuality dataQuality = sufficiencyCalculator.calculate(
			properties.policyVersion(),
			input.catalog(),
			evidence
		);
		String snapshotHash = hasher.hash(metrics, dataQuality, evidence);
		return new ReportSnapshot(
			metrics,
			dataQuality,
			evidence,
			input.sourceDataAsOf(),
			snapshotHash
		);
	}

	private ReportSnapshot.Progress progress(ReportSnapshotInput.ProgressRecord progress) {
		return new ReportSnapshot.Progress(
			progress.explainedPages(),
			progress.totalPages(),
			progress.progressRate(),
			progress.progressDataAvailable()
		);
	}

	private List<ReportSnapshotInput.SourceRecord> sourcesOf(
		List<ReportSnapshotInput.SourceRecord> sources,
		ReportSourceType sourceType
	) {
		return sources.stream()
			.filter(source -> source.sourceType() == sourceType)
			.toList();
	}

	private ReportSnapshot.Questions questions(
		List<ReportSnapshotInput.SourceRecord> sources,
		Instant recentSince
	) {
		List<ReportSnapshotInput.SourceRecord> questions = sourcesOf(
			sources,
			ReportSourceType.QA_QUESTION
		);
		long recentCount = questions.stream()
			.filter(question -> !question.occurredAt().isBefore(recentSince))
			.count();
		return new ReportSnapshot.Questions(questions.size(), recentCount);
	}

	private ReportSnapshot.Activity activity(
		List<ReportSnapshotInput.SourceRecord> sources
	) {
		List<ReportSnapshotInput.SourceRecord> sessions = sourcesOf(
			sources,
			ReportSourceType.SESSION
		);
		Instant lastActivityAt = sessions.stream()
			.map(ReportSnapshotInput.SourceRecord::occurredAt)
			.max(Instant::compareTo)
			.orElse(null);
		return new ReportSnapshot.Activity(sessions.size(), lastActivityAt);
	}
}
