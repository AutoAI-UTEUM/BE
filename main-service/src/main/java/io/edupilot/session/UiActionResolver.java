package io.edupilot.session;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class UiActionResolver {

	public List<UiAction> forPageTransition(
		PageStatus previousStatus,
		PageStatus currentStatus,
		int currentPage,
		Integer pageCount,
		boolean quizEligible
	) {
		if (currentStatus == null || currentStatus == previousStatus) {
			return List.of();
		}
		return switch (currentStatus) {
			case EXPLAINED -> quizEligible
				? List.of(UiAction.quizProposal())
				: nextLearning(currentPage, pageCount);
			case REPAIR_COMPLETED -> nextLearning(currentPage, pageCount);
			default -> List.of();
		};
	}

	public List<UiAction> nextLearning(
		int currentPage,
		Integer pageCount
	) {
		boolean lastPage = pageCount != null && currentPage == pageCount;
		return List.of(
			lastPage
				? UiAction.completeSession()
				: UiAction.moveNextPage()
		);
	}
}
