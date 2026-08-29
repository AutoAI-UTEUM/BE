package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.dto.OutlineResponse;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialOverview;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.quiz.QuizProperties;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class QuizProposalPolicyTest {

	@Mock
	private MaterialPageRepository materialPageRepository;

	@Mock
	private MaterialOverviewRepository overviewRepository;

	private QuizProposalPolicy policy;

	@BeforeEach
	void setUp() {
		policy = new QuizProposalPolicy(
			materialPageRepository,
			overviewRepository,
			new QuizProperties(new BigDecimal("0.6"), 200)
		);
	}

	@Test
	void unchangedOrNonExplainedStateNeverQueriesMaterial() {
		assertThat(policy.isEligible(
			10L, 1, 6, TurnEventType.EXPLAIN_CURRENT_PAGE,
			PageStatus.EXPLAINED, false
		)).isFalse();
		assertThat(policy.isEligible(
			10L, 1, 6, TurnEventType.EXPLAIN_CURRENT_PAGE,
			PageStatus.EXPLAINING, true
		)).isFalse();

		verifyNoInteractions(materialPageRepository, overviewRepository);
	}

	@Test
	void textBelowThresholdIsIneligibleInFallbackMode() {
		when(overviewRepository.findByMaterial_Id(10L))
			.thenReturn(Optional.empty());
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L, 2
		)).thenReturn(Optional.of(199));

		assertThat(explainEligibility(2, 6)).isFalse();
	}

	@Test
	void checkpointModeIgnoresTextGateAndOffersOnlyAtTriggerPages() {
		MaterialOverview overview = readyOverviewWithCheckpoints(
			6,
			List.of(
				checkpoint(2, 1, 2),
				checkpoint(6, 3, 6)
			),
			section(1, 2),
			section(3, 4),
			section(5, 6)
		);
		when(overviewRepository.findByMaterial_Id(10L))
			.thenReturn(Optional.of(overview));

		assertThat(explainEligibility(2, 6)).isTrue();
		assertThat(explainEligibility(3, 6)).isFalse();
		assertThat(explainEligibility(6, 6)).isTrue();

		verifyNoInteractions(materialPageRepository);
	}

	@Test
	void missingPendingAndFailedOverviewUseLegacyTextFallback() {
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L, 2
		)).thenReturn(Optional.of(200));
		when(overviewRepository.findByMaterial_Id(10L))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(pendingOverview()))
			.thenReturn(Optional.of(failedOverview()));

		assertThat(explainEligibility(2, 6)).isTrue();
		assertThat(explainEligibility(2, 6)).isTrue();
		assertThat(explainEligibility(2, 6)).isTrue();
	}

	@Test
	void completeReadyOutlineOffersQuizOnlyAtSectionEndPages() {
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L, 2
		)).thenReturn(Optional.of(250));
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L, 3
		)).thenReturn(Optional.of(250));
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L, 6
		)).thenReturn(Optional.of(250));
		when(overviewRepository.findByMaterial_Id(10L))
			.thenReturn(Optional.of(readyOverview(
				6,
				section(1, 2),
				section(3, 4),
				section(5, 6)
			)));

		assertThat(explainEligibility(2, 6)).isTrue();
		assertThat(explainEligibility(3, 6)).isFalse();
		assertThat(explainEligibility(6, 6)).isTrue();
	}

	@Test
	void incompleteLegacyReadyOutlineFallsBackToTextRule() {
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L, 3
		)).thenReturn(Optional.of(250));
		when(overviewRepository.findByMaterial_Id(10L))
			.thenReturn(Optional.of(readyOverview(
				6,
				section(1, 2),
				section(4, 6)
			)));

		assertThat(explainEligibility(3, 6)).isTrue();
	}

	@Test
	void totalPageMismatchFallsBackToTextRule() {
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L, 3
		)).thenReturn(Optional.of(250));
		when(overviewRepository.findByMaterial_Id(10L))
			.thenReturn(Optional.of(readyOverview(
				5,
				section(1, 2),
				section(3, 5)
			)));

		assertThat(explainEligibility(3, 6)).isTrue();
	}

	@Test
	void nonExplainEventPreservesExistingExplainedTransitionBehavior() {
		assertThat(policy.isEligible(
			10L, 2, 6, TurnEventType.USER_QUESTION,
			PageStatus.EXPLAINED, true
		)).isTrue();

		verifyNoInteractions(materialPageRepository, overviewRepository);
	}

	private boolean explainEligibility(int currentPage, int totalPages) {
		return policy.isEligible(
			10L,
			currentPage,
			totalPages,
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			PageStatus.EXPLAINED,
			true
		);
	}

	private MaterialOverview pendingOverview() {
		return MaterialOverview.createPending(material());
	}

	private MaterialOverview failedOverview() {
		MaterialOverview overview = pendingOverview();
		overview.markFailed(Instant.parse("2026-08-25T12:00:00Z"));
		return overview;
	}

	private MaterialOverview readyOverview(
		int totalPages,
		OutlineResponse.Section... sections
	) {
		return readyOverviewWithCheckpoints(
			totalPages,
			null,
			sections
		);
	}

	private MaterialOverview readyOverviewWithCheckpoints(
		int totalPages,
		List<OutlineResponse.QuizCheckpoint> checkpoints,
		OutlineResponse.Section... sections
	) {
		MaterialOverview overview = pendingOverview();
		overview.markReady(
			"overview",
			new OutlineResponse(
				"1.0",
				"summary",
				List.of(sections),
				checkpoints,
				totalPages,
				null
			)
		);
		return overview;
	}

	private OutlineResponse.QuizCheckpoint checkpoint(
		int triggerPage,
		int startPage,
		int endPage
	) {
		return new OutlineResponse.QuizCheckpoint(
			triggerPage,
			new OutlineResponse.Coverage(startPage, endPage)
		);
	}

	private OutlineResponse.Section section(int startPage, int endPage) {
		return new OutlineResponse.Section(
			"section",
			startPage,
			endPage,
			List.of("keyword")
		);
	}

	private LearningMaterial material() {
		LearningMaterial material = LearningMaterial.create(
			User.create("owner@example.com", "hash", "owner"),
			"material",
			"materials/policy.pdf"
		);
		material.markReady(6);
		return material;
	}
}
