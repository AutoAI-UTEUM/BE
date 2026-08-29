package io.edupilot.session;

import java.util.List;

import org.springframework.stereotype.Component;

import io.edupilot.ai.dto.OutlineResponse;
import io.edupilot.material.MaterialOverview;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.material.MaterialOverviewStatus;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.quiz.QuizProperties;

@Component
public class QuizProposalPolicy {

	private final MaterialPageRepository materialPageRepository;
	private final MaterialOverviewRepository overviewRepository;
	private final QuizProperties quizProperties;

	public QuizProposalPolicy(
		MaterialPageRepository materialPageRepository,
		MaterialOverviewRepository overviewRepository,
		QuizProperties quizProperties
	) {
		this.materialPageRepository = materialPageRepository;
		this.overviewRepository = overviewRepository;
		this.quizProperties = quizProperties;
	}

	public boolean isEligible(
		Long materialId,
		int currentPage,
		Integer totalPages,
		TurnEventType eventType,
		PageStatus finalPageStatus,
		boolean pageStatusChanged
	) {
		if (finalPageStatus != PageStatus.EXPLAINED || !pageStatusChanged) {
			return false;
		}
		if (eventType != TurnEventType.EXPLAIN_CURRENT_PAGE) {
			return true;
		}

		MaterialOverview overview = overviewRepository
			.findByMaterial_Id(materialId)
			.orElse(null);
		List<OutlineResponse.QuizCheckpoint> checkpoints = quizCheckpoints(
			overview,
			totalPages
		);
		if (checkpoints != null) {
			return checkpoints.stream()
				.anyMatch(checkpoint ->
					checkpoint.triggerPage() == currentPage);
		}

		int textLength = materialPageRepository
			.findTextLengthByMaterialIdAndPageNumber(materialId, currentPage)
			.orElse(0);
		if (textLength < quizProperties.proposalMinPageTextLength()) {
			return false;
		}

		return overview == null || eligibleWithOverview(
			overview,
			currentPage,
			totalPages
		);
	}

	private List<OutlineResponse.QuizCheckpoint> quizCheckpoints(
		MaterialOverview overview,
		Integer totalPages
	) {
		if (overview == null
			|| overview.getStatus() != MaterialOverviewStatus.READY
			|| totalPages == null) {
			return null;
		}
		OutlineResponse outline = overview.getOutline();
		if (outline == null || outline.totalPages() != totalPages) {
			return null;
		}
		List<OutlineResponse.QuizCheckpoint> checkpoints =
			outline.quizCheckpoints();
		return checkpoints == null || checkpoints.isEmpty()
			? null
			: checkpoints;
	}

	private boolean eligibleWithOverview(
		MaterialOverview overview,
		int currentPage,
		Integer totalPages
	) {
		if (overview.getStatus() != MaterialOverviewStatus.READY) {
			return true;
		}
		OutlineResponse outline = overview.getOutline();
		if (!hasCompleteCoverage(outline, totalPages)) {
			return true;
		}
		return outline.sections().stream()
			.anyMatch(section -> section.endPage() == currentPage);
	}

	private boolean hasCompleteCoverage(
		OutlineResponse outline,
		Integer totalPages
	) {
		if (outline == null || totalPages == null || totalPages < 1
			|| outline.totalPages() != totalPages) {
			return false;
		}
		List<OutlineResponse.Section> sections = outline.sections();
		if (sections == null || sections.isEmpty()) {
			return false;
		}

		int expectedStartPage = 1;
		for (OutlineResponse.Section section : sections) {
			if (section == null
				|| section.startPage() != expectedStartPage
				|| section.startPage() < 1
				|| section.endPage() < section.startPage()
				|| section.endPage() > totalPages) {
				return false;
			}
			expectedStartPage = section.endPage() + 1;
		}
		return expectedStartPage == totalPages + 1;
	}
}
