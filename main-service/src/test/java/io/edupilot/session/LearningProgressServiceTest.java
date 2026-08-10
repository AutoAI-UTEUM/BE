package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.Classroom;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;

@ExtendWith(MockitoExtension.class)
class LearningProgressServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private LearningMaterialRepository materialRepository;
	@Mock
	private SessionPageRecordRepository pageRecordRepository;
	@Mock
	private ClassroomRepository classroomRepository;
	@Mock
	private ClassroomMemberRepository memberRepository;
	@Mock
	private ClassroomWeekMaterialRepository weekMaterialRepository;

	@Test
	void roundsThreeExplainedPagesOfNinetySixToThreePercent() {
		LearningSession session = org.mockito.Mockito.mock(
			LearningSession.class
		);
		when(session.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(session.getMaterialPageCount()).thenReturn(96);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(pageRecordRepository.countBySessionId(100L)).thenReturn(3L);

		assertThat(service().calculateSessionProgressRate(1L, 100L))
			.isEqualTo(3);
	}

	@Test
	void returnsZeroWhenSessionHasNoExplanationHistory() {
		LearningSession session = org.mockito.Mockito.mock(
			LearningSession.class
		);
		when(session.getStatus()).thenReturn(SessionStatus.COMPLETED);
		when(session.getMaterialPageCount()).thenReturn(96);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));

		assertThat(service().calculateSessionProgressRate(1L, 100L))
			.isZero();
	}

	@Test
	void calculatesMaterialRateFromDistinctPageCount() {
		LearningMaterial material = org.mockito.Mockito.mock(
			LearningMaterial.class
		);
		when(material.getPageCount()).thenReturn(10);
		when(materialRepository.findById(20L))
			.thenReturn(Optional.of(material));
		when(pageRecordRepository.countDistinctByUserIdAndMaterialId(
			1L,
			20L
		)).thenReturn(4L);

		assertThat(service().calculateMaterialProgressRate(1L, 20L))
			.isEqualTo(40);
	}

	@Test
	void rejectsDeletedSession() {
		LearningSession session = org.mockito.Mockito.mock(
			LearningSession.class
		);
		when(session.getStatus()).thenReturn(SessionStatus.DELETED);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));

		assertThatThrownBy(() ->
			service().calculateSessionProgressRate(1L, 100L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.SESSION_NOT_FOUND)
			);
		verify(pageRecordRepository, never()).countBySessionId(100L);
	}

	@Test
	void rejectsMissingMaterial() {
		when(materialRepository.findById(20L)).thenReturn(Optional.empty());

		assertThatThrownBy(() ->
			service().calculateMaterialProgressRate(1L, 20L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
		verify(pageRecordRepository, never())
			.countDistinctByUserIdAndMaterialId(1L, 20L);
	}

	@Test
	void calculatesClassroomRateAcrossDistinctReleasedReadyMaterials() {
		Classroom classroom = org.mockito.Mockito.mock(Classroom.class);
		LearningMaterial material = org.mockito.Mockito.mock(LearningMaterial.class);
		when(classroom.getInstructorId()).thenReturn(9L);
		when(classroomRepository.findWithInstructorById(30L))
			.thenReturn(Optional.of(classroom));
		when(memberRepository.existsByClassroom_IdAndUser_Id(30L, 1L))
			.thenReturn(true);
		when(weekMaterialRepository.findDistinctVisibleReadyMaterials(
			30L,
			Instant.parse("2026-08-02T00:00:00Z"),
			io.edupilot.material.MaterialStatus.ACTIVE,
			io.edupilot.material.MaterialProcessingStatus.READY
		)).thenReturn(List.of(material));
		when(material.getId()).thenReturn(20L);
		when(material.getPageCount()).thenReturn(96);
		when(pageRecordRepository.countDistinctByUserIdAndMaterialId(1L, 20L))
			.thenReturn(3L);

		assertThat(service().calculateClassroomProgressRate(1L, 30L))
			.isEqualTo(3);
	}

	@Test
	void reportProgressUsesDistinctExplainedPagesWithoutInferringSkippedPages() {
		LearningMaterial material = org.mockito.Mockito.mock(LearningMaterial.class);
		when(material.getId()).thenReturn(20L);
		when(material.getPageCount()).thenReturn(5);
		when(pageRecordRepository.countDistinctByUserIdAndMaterialId(1L, 20L))
			.thenReturn(2L);

		LearningProgressService.ReportProgress progress = service()
			.calculateReportProgress(1L, List.of(material));

		assertThat(progress.explainedPages()).isEqualTo(2);
		assertThat(progress.totalPages()).isEqualTo(5);
		assertThat(progress.progressRate()).isEqualTo(40);
		assertThat(progress.progressDataAvailable()).isTrue();
	}

	@Test
	void classroomAverageProgressUsesOneBatchForAllLearnersAndMaterials() {
		LearningMaterial first = org.mockito.Mockito.mock(LearningMaterial.class);
		LearningMaterial second = org.mockito.Mockito.mock(LearningMaterial.class);
		when(first.getId()).thenReturn(10L);
		when(first.getPageCount()).thenReturn(100);
		when(second.getId()).thenReturn(20L);
		when(second.getPageCount()).thenReturn(50);
		when(pageRecordRepository.findClassroomProgressCounts(
			30L,
			java.util.Set.of(10L, 20L)
		)).thenReturn(List.of(
			new SessionPageRecordRepository.UserMaterialProgressCount(1L, 10L, 50L),
			new SessionPageRecordRepository.UserMaterialProgressCount(2L, 10L, 25L),
			new SessionPageRecordRepository.UserMaterialProgressCount(1L, 20L, 50L)
		));

		var snapshot = service().calculateClassroomProgressSnapshot(
			30L,
			List.of(first, second),
			3L
		);

		assertThat(snapshot.averageProgressRate(List.of(first, second))).isEqualTo(28);
		assertThat(snapshot.materialAverageProgressRate(first)).isEqualTo(25);
		assertThat(snapshot.materialAverageProgressRate(second)).isEqualTo(33);
		verify(pageRecordRepository).findClassroomProgressCounts(
			30L,
			java.util.Set.of(10L, 20L)
		);
	}

	@Test
	void studentProgressRatesReuseClassroomBatchAndWeightedPageDefinition() {
		LearningMaterial first = org.mockito.Mockito.mock(LearningMaterial.class);
		LearningMaterial second = org.mockito.Mockito.mock(LearningMaterial.class);
		when(first.getId()).thenReturn(10L);
		when(first.getPageCount()).thenReturn(100);
		when(second.getId()).thenReturn(20L);
		when(second.getPageCount()).thenReturn(50);
		when(pageRecordRepository.findClassroomProgressCounts(
			30L,
			java.util.Set.of(10L, 20L)
		)).thenReturn(List.of(
			new SessionPageRecordRepository.UserMaterialProgressCount(1L, 10L, 50L),
			new SessionPageRecordRepository.UserMaterialProgressCount(1L, 20L, 25L),
			new SessionPageRecordRepository.UserMaterialProgressCount(2L, 10L, 30L),
			new SessionPageRecordRepository.UserMaterialProgressCount(99L, 10L, 100L)
		));

		Map<Long, Integer> rates = service().calculateStudentProgressRates(
			30L,
			List.of(first, second),
			List.of(1L, 2L, 3L)
		);

		assertThat(rates).containsExactlyInAnyOrderEntriesOf(Map.of(
			1L, 50,
			2L, 20,
			3L, 0
		));
		verify(pageRecordRepository).findClassroomProgressCounts(
			30L,
			java.util.Set.of(10L, 20L)
		);
	}

	private LearningProgressService service() {
		return new LearningProgressService(
			sessionRepository,
			materialRepository,
			pageRecordRepository,
			classroomRepository,
			memberRepository,
			weekMaterialRepository,
			Clock.fixed(
				Instant.parse("2026-08-02T00:00:00Z"),
				ZoneOffset.UTC
			)
		);
	}
}
