package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialProcessingStatus;
import io.edupilot.material.MaterialStatus;

class ClassroomWeekVisibilityTest {

	private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
	private static final Instant PAST = NOW.minusSeconds(1);
	private static final Instant FUTURE = NOW.plusSeconds(1);

	@Test
	void learnerVisibilityMatchesEveryStatusAndReleaseAtCombination() {
		assertVisibility(ClassroomWeekStatus.PRIVATE, false, false, false);
		assertVisibility(ClassroomWeekStatus.SCHEDULED, true, false, false);
		assertVisibility(ClassroomWeekStatus.PUBLISHED, true, true, true);
		assertVisibility(ClassroomWeekStatus.BREAK, true, true, true);
	}

	@Test
	void allMaterialCandidatesIgnoreWeekVisibilityWhileVisibleQueriesRemain() {
		ClassroomWeekMaterialRepository repository = mock(
			ClassroomWeekMaterialRepository.class,
			CALLS_REAL_METHODS
		);
		LearningMaterial hiddenMaterial = mock(LearningMaterial.class);
		LearningMaterial breakMaterial = mock(LearningMaterial.class);
		ClassroomWeekMaterial privateLink = ClassroomWeekMaterial.create(
			week(ClassroomWeekStatus.PRIVATE, PAST),
			hiddenMaterial,
			NOW
		);
		ClassroomWeekMaterial breakLink = ClassroomWeekMaterial.create(
			week(ClassroomWeekStatus.BREAK, FUTURE),
			breakMaterial,
			NOW
		);
		when(repository.findAccessCandidates(2L, 10L))
			.thenReturn(List.of(privateLink));
		when(repository.findAccessCandidates(2L, 20L))
			.thenReturn(List.of(breakLink));
		when(repository.findReadyMaterialCandidates(
			30L,
			MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY
		)).thenReturn(List.of(privateLink, breakLink));

		assertThat(repository.existsVisibleAccess(2L, 10L, NOW)).isFalse();
		assertThat(repository.existsVisibleAccess(2L, 20L, NOW)).isTrue();
		assertThat(repository.existsAccess(2L, 10L)).isTrue();
		assertThat(repository.existsAccess(2L, 20L)).isTrue();
		assertThat(repository.findDistinctVisibleReadyMaterials(
			30L,
			NOW,
			MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY
		)).containsExactly(breakMaterial);
		assertThat(repository.findDistinctReadyMaterials(
			30L,
			MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY
		)).containsExactly(hiddenMaterial, breakMaterial);
	}

	private void assertVisibility(
		ClassroomWeekStatus status,
		boolean pastVisible,
		boolean futureVisible,
		boolean nullVisible
	) {
		assertThat(week(status, PAST).isVisibleToLearner(NOW))
			.isEqualTo(pastVisible);
		assertThat(week(status, FUTURE).isVisibleToLearner(NOW))
			.isEqualTo(futureVisible);
		assertThat(week(status, null).isVisibleToLearner(NOW))
			.isEqualTo(nullVisible);
	}

	private ClassroomWeek week(ClassroomWeekStatus status, Instant releaseAt) {
		return ClassroomWeek.create(
			mock(Classroom.class),
			1,
			"Week 1",
			releaseAt,
			status,
			1
		);
	}
}
