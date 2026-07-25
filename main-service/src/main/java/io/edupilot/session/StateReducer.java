package io.edupilot.session;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class StateReducer {

	public PageTransition movePage(int currentPage, int requestedPage) {
		if (currentPage == requestedPage) {
			return PageTransition.unchanged(currentPage);
		}
		return new PageTransition(
			requestedPage,
			PageStatus.NOT_EXPLAINED,
			List.of(UiAction.pageExplanation()),
			true
		);
	}

	public record PageTransition(
		int pageNumber,
		PageStatus pageStatus,
		List<UiAction> uiActions,
		boolean changed
	) {
		private static PageTransition unchanged(int currentPage) {
			return new PageTransition(currentPage, null, List.of(), false);
		}
	}
}
