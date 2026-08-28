package io.edupilot.ai.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record OutlineResponse(
	String schemaVersion,
	String materialSummary,
	List<Section> sections,
	List<QuizCheckpoint> quizCheckpoints,
	int totalPages
) {
	private static final Logger log = LoggerFactory.getLogger(
		OutlineResponse.class
	);

	public OutlineResponse {
		quizCheckpoints = validatedCheckpoints(
			quizCheckpoints,
			sections,
			totalPages
		);
	}

	public OutlineResponse(
		String schemaVersion,
		String materialSummary,
		List<Section> sections,
		int totalPages
	) {
		this(schemaVersion, materialSummary, sections, null, totalPages);
	}

	public record Section(
		String title,
		String description,
		int startPage,
		int endPage,
		List<String> keywords
	) {
		public Section(
			String title,
			int startPage,
			int endPage,
			List<String> keywords
		) {
			this(title, null, startPage, endPage, keywords);
		}
	}

	public record QuizCheckpoint(
		int triggerPage,
		Coverage coverage
	) {
	}

	public record Coverage(
		int startPage,
		int endPage
	) {
	}

	private static List<QuizCheckpoint> validatedCheckpoints(
		List<QuizCheckpoint> checkpoints,
		List<Section> sections,
		int totalPages
	) {
		if (checkpoints == null) {
			return null;
		}
		if (checkpoints.isEmpty() || checkpoints.size() > 10) {
			return rejected(CheckpointViolation.COUNT_OUT_OF_RANGE);
		}

		Set<Integer> sectionStarts = new HashSet<>();
		Set<Integer> sectionEnds = new HashSet<>();
		if (sections != null) {
			for (Section section : sections) {
				if (section != null) {
					sectionStarts.add(section.startPage());
					sectionEnds.add(section.endPage());
				}
			}
		}

		Set<Integer> triggerPages = new HashSet<>();
		int previousTriggerPage = 0;
		int previousCoverageEnd = 0;
		for (QuizCheckpoint checkpoint : checkpoints) {
			if (checkpoint == null || checkpoint.coverage() == null) {
				return rejected(CheckpointViolation.MALFORMED);
			}
			Coverage coverage = checkpoint.coverage();
			if (totalPages < 1
				|| checkpoint.triggerPage() < 1
				|| checkpoint.triggerPage() > totalPages
				|| coverage.startPage() < 1
				|| coverage.startPage() > totalPages
				|| coverage.endPage() < 1
				|| coverage.endPage() > totalPages) {
				return rejected(CheckpointViolation.RANGE_OUT_OF_BOUNDS);
			}
			if (coverage.startPage() > coverage.endPage()) {
				return rejected(CheckpointViolation.RANGE_REVERSED);
			}
			if (checkpoint.triggerPage() != coverage.endPage()) {
				return rejected(CheckpointViolation.TRIGGER_MISMATCH);
			}
			if (!triggerPages.add(checkpoint.triggerPage())) {
				return rejected(CheckpointViolation.DUPLICATE_TRIGGER);
			}
			if (checkpoint.triggerPage() < previousTriggerPage) {
				return rejected(CheckpointViolation.TRIGGER_ORDER_INVALID);
			}
			if (coverage.startPage() <= previousCoverageEnd) {
				return rejected(CheckpointViolation.COVERAGE_OVERLAP);
			}
			if (!sectionStarts.contains(coverage.startPage())
				|| !sectionEnds.contains(coverage.endPage())) {
				return rejected(
					CheckpointViolation.SECTION_BOUNDARY_MISMATCH
				);
			}
			previousTriggerPage = checkpoint.triggerPage();
			previousCoverageEnd = coverage.endPage();
		}
		return List.copyOf(checkpoints);
	}

	private static List<QuizCheckpoint> rejected(
		CheckpointViolation violation
	) {
		log.atWarn()
			.addKeyValue("violationType", violation.name())
			.log("Ignored invalid outline quiz checkpoints");
		return null;
	}

	private enum CheckpointViolation {
		COUNT_OUT_OF_RANGE,
		MALFORMED,
		RANGE_OUT_OF_BOUNDS,
		RANGE_REVERSED,
		TRIGGER_MISMATCH,
		DUPLICATE_TRIGGER,
		TRIGGER_ORDER_INVALID,
		COVERAGE_OVERLAP,
		SECTION_BOUNDARY_MISMATCH
	}
}
