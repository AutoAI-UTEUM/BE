package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UiActionResolverTest {

	private final UiActionResolver resolver = new UiActionResolver();

	@Test
	void createsW3WhenPageBecomesExplained() {
		assertThat(resolver.forPageTransition(
			PageStatus.EXPLAINING,
			PageStatus.EXPLAINED,
			1,
			3
		)).containsExactly(new UiAction(
			"BINARY_DECISION",
			"퀴즈를 진행할까요?",
			"SHOW_QUIZ_TYPE_SELECT",
			"MOVE_NEXT_PAGE",
			null
		));
	}

	@Test
	void createsW5ForNextPageAndLastPageBoundaries() {
		assertThat(resolver.nextLearning(2, 3))
			.containsExactly(UiAction.moveNextPage());
		assertThat(resolver.nextLearning(3, 3))
			.containsExactly(UiAction.completeSession());
	}

	@Test
	void createsW7WithTheSameBoundaryRuleAsW5() {
		assertThat(resolver.forPageTransition(
			PageStatus.DIAGNOSIS_PENDING,
			PageStatus.REPAIR_COMPLETED,
			2,
			3
		)).containsExactly(UiAction.moveNextPage());
		assertThat(resolver.forPageTransition(
			PageStatus.DIAGNOSIS_PENDING,
			PageStatus.REPAIR_COMPLETED,
			3,
			3
		)).containsExactly(UiAction.completeSession());
	}

	@Test
	void returnsNoWidgetWithoutAStateTransition() {
		assertThat(resolver.forPageTransition(
			PageStatus.EXPLAINED,
			PageStatus.EXPLAINED,
			1,
			3
		)).isEmpty();
	}
}
