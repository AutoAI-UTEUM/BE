package io.edupilot.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class ReportScoreCalculator {

	private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

	private final ReportProperties properties;

	public ReportScoreCalculator(ReportProperties properties) {
		this.properties = properties;
	}

	public BigDecimal normalize(BigDecimal score, BigDecimal maxScore) {
		if (score == null || maxScore == null || maxScore.signum() <= 0) {
			throw new IllegalArgumentException("score and positive maxScore are required");
		}
		return score.multiply(ONE_HUNDRED)
			.divide(maxScore, 2, RoundingMode.HALF_UP);
	}

	public ReportSnapshot.ScoreWindow aggregate(
		List<ReportSnapshotInput.SourceRecord> records,
		Instant recentSince
	) {
		List<BigDecimal> cumulative = records.stream()
			.map(this::normalizedScore)
			.toList();
		List<BigDecimal> recent = records.stream()
			.filter(record -> !record.occurredAt().isBefore(recentSince))
			.map(this::normalizedScore)
			.toList();
		return new ReportSnapshot.ScoreWindow(
			aggregateScores(cumulative),
			aggregateScores(recent)
		);
	}

	public OverallResult overall(List<CriterionScore> scores) {
		BigDecimal weightedSum = BigDecimal.ZERO;
		BigDecimal totalWeight = BigDecimal.ZERO;
		for (CriterionScore score : scores) {
			if (score.status() != ReportCriterionStatus.ASSESSED || score.score() == null) {
				continue;
			}
			weightedSum = weightedSum.add(score.score().multiply(score.weight()));
			totalWeight = totalWeight.add(score.weight());
		}
		if (totalWeight.signum() == 0) {
			return new OverallResult(null, null);
		}
		BigDecimal overallScore = weightedSum.divide(totalWeight, 2, RoundingMode.HALF_UP);
		return new OverallResult(overallScore, stage(overallScore));
	}

	public String stage(BigDecimal overallScore) {
		if (overallScore == null) {
			return null;
		}
		if (overallScore.compareTo(properties.stageExcellent()) >= 0) {
			return "우수";
		}
		if (overallScore.compareTo(properties.stageGood()) >= 0) {
			return "양호";
		}
		if (overallScore.compareTo(properties.stageFair()) >= 0) {
			return "보통";
		}
		return "보완 필요";
	}

	public ReportTrend trend(BigDecimal previousScore, BigDecimal currentScore) {
		if (previousScore == null || currentScore == null) {
			return null;
		}
		BigDecimal difference = currentScore.subtract(previousScore);
		if (difference.compareTo(properties.trendThreshold()) > 0) {
			return ReportTrend.UP;
		}
		if (difference.compareTo(properties.trendThreshold().negate()) < 0) {
			return ReportTrend.DOWN;
		}
		return ReportTrend.FLAT;
	}

	private BigDecimal normalizedScore(ReportSnapshotInput.SourceRecord record) {
		if (record.normalizedScore() != null) {
			return record.normalizedScore().setScale(2, RoundingMode.HALF_UP);
		}
		return normalize(record.score(), record.maxScore());
	}

	private ReportSnapshot.ScoreAggregate aggregateScores(List<BigDecimal> scores) {
		if (scores.isEmpty()) {
			return new ReportSnapshot.ScoreAggregate(0, null);
		}
		BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		return new ReportSnapshot.ScoreAggregate(
			scores.size(),
			sum.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP)
		);
	}

	public record CriterionScore(
		String criterionKey,
		BigDecimal score,
		ReportCriterionStatus status,
		BigDecimal weight
	) {
	}

	public record OverallResult(
		BigDecimal score,
		String stage
	) {
	}
}
