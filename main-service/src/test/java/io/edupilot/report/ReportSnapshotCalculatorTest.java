package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ReportSnapshotCalculatorTest {

	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
	private static final ReportProperties PROPERTIES = new ReportProperties(
		Duration.ofDays(14),
		200,
		new BigDecimal("5.0"),
		new BigDecimal("85"),
		new BigDecimal("70"),
		new BigDecimal("50"),
		"1.0"
	);

	private final ReportEvidenceSelector evidenceSelector = new ReportEvidenceSelector();
	private final ReportScoreCalculator scoreCalculator = new ReportScoreCalculator(PROPERTIES);
	private final ReportSnapshotCalculator calculator = new ReportSnapshotCalculator(
		PROPERTIES,
		evidenceSelector,
		scoreCalculator,
		new ReportSufficiencyCalculator(),
		new ReportSnapshotHasher()
	);

	@Test
	void separatesCumulativeAndRecentAtInclusiveFourteenDayBoundary() {
		Instant boundary = NOW.minus(Duration.ofDays(14));
		List<ReportSnapshotInput.SourceRecord> sources = List.of(
			scoreSource(
				ReportSourceType.QUIZ_SUBMISSION,
				"before",
				boundary.minusSeconds(1),
				"50",
				"100",
				null
			),
			scoreSource(
				ReportSourceType.QUIZ_SUBMISSION,
				"boundary",
				boundary,
				"80",
				"100",
				null
			),
			scoreSource(
				ReportSourceType.QUIZ_SUBMISSION,
				"after",
				boundary.plusSeconds(1),
				"100",
				"100",
				null
			),
			scoreSource(
				ReportSourceType.EXAM_SUBMISSION,
				"exam",
				boundary,
				"10",
				"20",
				"80.00"
			),
			source(ReportSourceType.QA_QUESTION, "question-before", boundary.minusSeconds(1)),
			source(ReportSourceType.QA_QUESTION, "question-boundary", boundary),
			source(ReportSourceType.QA_QUESTION, "question-after", boundary.plusSeconds(1))
		);

		ReportSnapshot snapshot = calculator.compute(input(sources, List.of()));

		assertThat(snapshot.metrics().quiz().cumulative().submissionCount()).isEqualTo(3);
		assertThat(snapshot.metrics().quiz().cumulative().averageNormalizedScore())
			.isEqualByComparingTo("76.67");
		assertThat(snapshot.metrics().quiz().recent().submissionCount()).isEqualTo(2);
		assertThat(snapshot.metrics().quiz().recent().averageNormalizedScore())
			.isEqualByComparingTo("90.00");
		assertThat(snapshot.metrics().exam().recent().averageNormalizedScore())
			.isEqualByComparingTo("80.00");
		assertThat(snapshot.metrics().questions().cumulativeCount()).isEqualTo(3);
		assertThat(snapshot.metrics().questions().recentCount()).isEqualTo(2);
	}

	@Test
	void marksInsufficientCriteriaWithoutProducingAnOverallScore() {
		ReportCriterionDefinition questionCriterion = criterion(
			"question",
			Set.of(ReportSourceType.QA_QUESTION),
			2
		);
		ReportCriterionDefinition memoryCriterion = criterion(
			"memory",
			Set.of(ReportSourceType.MEMORY),
			2
		);

		ReportSnapshot snapshot = calculator.compute(input(
			List.of(source(ReportSourceType.QA_QUESTION, "one", NOW)),
			List.of(questionCriterion, memoryCriterion)
		));

		assertThat(snapshot.dataQuality().criterionEligibility().get("question"))
			.satisfies(eligibility -> {
				assertThat(eligibility.eligible()).isFalse();
				assertThat(eligibility.reason()).isEqualTo(
					ReportSufficiencyCalculator.INSUFFICIENT_EVIDENCE
				);
				assertThat(eligibility.evidenceCount()).isEqualTo(1);
			});
		assertThat(snapshot.dataQuality().criterionEligibility().get("memory").reason())
			.isEqualTo(ReportSufficiencyCalculator.NO_ALLOWED_SOURCE_DATA);
		assertThat(scoreCalculator.overall(List.of(
			new ReportScoreCalculator.CriterionScore(
				"question",
				new BigDecimal("100"),
				ReportCriterionStatus.INSUFFICIENT_DATA,
				BigDecimal.ONE
			)
		)).score()).isNull();
	}

	@Test
	void selectsAssessmentEvidenceFirstAndHashesSameDataDeterministically() {
		List<ReportSnapshotInput.SourceRecord> sources = new ArrayList<>();
		for (int index = 0; index < 205; index++) {
			sources.add(source(
				ReportSourceType.SESSION,
				"session-" + index,
				NOW.minusSeconds(index)
			));
		}
		for (int index = 0; index < 4; index++) {
			sources.add(scoreSource(
				index == 0
					? ReportSourceType.EXAM_SUBMISSION
					: ReportSourceType.QUIZ_SUBMISSION,
				"assessment-" + index,
				NOW.minusSeconds(10_000L + index),
				"80",
				"100",
				null
			));
		}
		sources.add(sources.getFirst());

		ReportSnapshot first = calculator.compute(input(sources, List.of()));
		List<ReportSnapshotInput.SourceRecord> shuffled = new ArrayList<>(sources);
		Collections.reverse(shuffled);
		ReportSnapshot second = calculator.compute(input(shuffled, List.of()));

		assertThat(first.evidence()).hasSize(200);
		assertThat(first.evidence().stream()
			.filter(item -> item.sourceType() == ReportSourceType.EXAM_SUBMISSION
				|| item.sourceType() == ReportSourceType.QUIZ_SUBMISSION))
			.hasSize(4);
		assertThat(first.metrics().sessions().sessionCount()).isEqualTo(205);
		assertThat(second.evidence()).isEqualTo(first.evidence());
		assertThat(second.snapshotHash()).isEqualTo(first.snapshotHash());
		assertThat(first.snapshotHash()).hasSize(64);
	}

	@Test
	void evidenceIdUsesStableSourceTypeAndSourceRefSha256() {
		assertThat(evidenceSelector.evidenceId(
			ReportSourceType.SESSION,
			"session:1"
		)).isEqualTo("46d8289345e92ee263b83ca4a6969614bca624259684040833312cfca60f3690");
	}

	@Test
	void calculatesOverallScoreFromAssessedCriteriaOnlyAtStageBoundaries() {
		assertThat(scoreCalculator.stage(new BigDecimal("85"))).isEqualTo("우수");
		assertThat(scoreCalculator.stage(new BigDecimal("70"))).isEqualTo("양호");
		assertThat(scoreCalculator.stage(new BigDecimal("50"))).isEqualTo("보통");
		assertThat(scoreCalculator.stage(new BigDecimal("49.99"))).isEqualTo("보완 필요");

		ReportScoreCalculator.OverallResult result = scoreCalculator.overall(List.of(
			new ReportScoreCalculator.CriterionScore(
				"assessed",
				new BigDecimal("85"),
				ReportCriterionStatus.ASSESSED,
				BigDecimal.ONE
			),
			new ReportScoreCalculator.CriterionScore(
				"insufficient",
				new BigDecimal("0"),
				ReportCriterionStatus.INSUFFICIENT_DATA,
				new BigDecimal("100")
			)
		));
		assertThat(result.score()).isEqualByComparingTo("85.00");
		assertThat(result.stage()).isEqualTo("우수");
		assertThat(scoreCalculator.overall(List.of()).score()).isNull();
	}

	@Test
	void calculatesTrendOnlyOutsideConfiguredThreshold() {
		assertThat(scoreCalculator.trend(new BigDecimal("70"), new BigDecimal("75.01")))
			.isEqualTo(ReportTrend.UP);
		assertThat(scoreCalculator.trend(new BigDecimal("70"), new BigDecimal("75")))
			.isEqualTo(ReportTrend.FLAT);
		assertThat(scoreCalculator.trend(new BigDecimal("70"), new BigDecimal("65")))
			.isEqualTo(ReportTrend.FLAT);
		assertThat(scoreCalculator.trend(new BigDecimal("70"), new BigDecimal("64.99")))
			.isEqualTo(ReportTrend.DOWN);
		assertThat(scoreCalculator.trend(null, new BigDecimal("70"))).isNull();
		assertThat(scoreCalculator.trend(new BigDecimal("70"), null)).isNull();
	}

	private ReportSnapshotInput input(
		List<ReportSnapshotInput.SourceRecord> sources,
		List<ReportCriterionDefinition> catalog
	) {
		return new ReportSnapshotInput(
			catalog,
			sources,
			new ReportSnapshotInput.ProgressRecord(0, 10, 0, false),
			NOW
		);
	}

	private ReportCriterionDefinition criterion(
		String key,
		Set<ReportSourceType> sources,
		int minEvidence
	) {
		return new ReportCriterionDefinition(
			key,
			key,
			"rubric",
			sources,
			minEvidence,
			BigDecimal.ONE,
			"1.0"
		);
	}

	private ReportSnapshotInput.SourceRecord source(
		ReportSourceType sourceType,
		String sourceRef,
		Instant occurredAt
	) {
		return new ReportSnapshotInput.SourceRecord(
			sourceType,
			sourceRef,
			occurredAt,
			"label-" + sourceRef,
			Map.of("ref", sourceRef),
			null,
			null,
			null
		);
	}

	private ReportSnapshotInput.SourceRecord scoreSource(
		ReportSourceType sourceType,
		String sourceRef,
		Instant occurredAt,
		String score,
		String maxScore,
		String normalizedScore
	) {
		return new ReportSnapshotInput.SourceRecord(
			sourceType,
			sourceRef,
			occurredAt,
			"score-" + sourceRef,
			Map.of("ref", sourceRef),
			new BigDecimal(score),
			new BigDecimal(maxScore),
			normalizedScore == null ? null : new BigDecimal(normalizedScore)
		);
	}
}
