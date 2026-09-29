package io.edupilot.ai.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record OutlineResponse(
	String schemaVersion,
	String materialSummary,
	List<Section> sections,
	List<QuizCheckpoint> quizCheckpoints,
	@JsonInclude(JsonInclude.Include.NON_NULL) List<PageQuizPlan> pageQuizPlan,
	int totalPages,
	AiUsage usage
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
		pageQuizPlan = validatedPageQuizPlan(pageQuizPlan, totalPages);
	}

	public OutlineResponse(
		String schemaVersion,
		String materialSummary,
		List<Section> sections,
		List<QuizCheckpoint> quizCheckpoints,
		int totalPages,
		AiUsage usage
	) {
		this(schemaVersion, materialSummary, sections, quizCheckpoints,
			null, totalPages, usage);
	}

	public OutlineResponse withoutPageQuizPlan() {
		return pageQuizPlan == null ? this : new OutlineResponse(
			schemaVersion, materialSummary, sections, quizCheckpoints,
			null, totalPages, usage
		);
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

	public record PageQuizPlan(
		int pageNumber,
		Boolean suggestQuiz,
		String reason
	) {
	}

	private static List<PageQuizPlan> validatedPageQuizPlan(
		List<PageQuizPlan> plans,
		int totalPages
	) {
		if (plans == null) {
			return null;
		}
		if (totalPages < 1 || plans.size() != totalPages) {
			return rejectedPageQuizPlan(PageQuizPlanViolation.COUNT_MISMATCH);
		}
		Set<Integer> seen = new HashSet<>();
		for (int index = 0; index < plans.size(); index++) {
			PageQuizPlan plan = plans.get(index);
			if (plan == null || plan.suggestQuiz() == null
				|| plan.reason() == null || plan.reason().isBlank()
				|| plan.reason().length() > 240) {
				return rejectedPageQuizPlan(PageQuizPlanViolation.MALFORMED);
			}
			if (plan.pageNumber() < 1 || plan.pageNumber() > totalPages) {
				return rejectedPageQuizPlan(PageQuizPlanViolation.OUT_OF_RANGE);
			}
			if (!seen.add(plan.pageNumber())) {
				return rejectedPageQuizPlan(PageQuizPlanViolation.DUPLICATE_PAGE);
			}
			if (plan.pageNumber() != index + 1) {
				return rejectedPageQuizPlan(PageQuizPlanViolation.ORDER_INVALID);
			}
		}
		return List.copyOf(plans);
	}

	private static List<PageQuizPlan> rejectedPageQuizPlan(
		PageQuizPlanViolation violation
	) {
		log.atWarn()
			.addKeyValue("violationType", violation.name())
			.log("Ignored invalid outline page quiz plan");
		return null;
	}

	private enum PageQuizPlanViolation {
		COUNT_MISMATCH,
		MALFORMED,
		OUT_OF_RANGE,
		DUPLICATE_PAGE,
		ORDER_INVALID
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
