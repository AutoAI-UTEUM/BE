package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.QaMessageRepository;
import io.edupilot.session.StudentLastActivity;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ClassroomAnalyticsServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-04T03:00:00Z");

	@Mock
	private ClassroomService classroomService;
	@Mock
	private ClassroomMemberRepository memberRepository;
	@Mock
	private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private QaMessageRepository qaMessageRepository;
	@Mock
	private LearningProgressService progressService;

	private ClassroomAnalyticsService service;
	private LearningMaterial firstMaterial;
	private LearningMaterial secondMaterial;

	@BeforeEach
	void setUp() {
		service = new ClassroomAnalyticsService(
			classroomService,
			memberRepository,
			weekMaterialRepository,
			sessionRepository,
			qaMessageRepository,
			progressService,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		User instructor = User.create(
			"teacher@example.com",
			"hash",
			"Instructor",
			UserRole.INSTRUCTOR
		);
		ReflectionTestUtils.setField(instructor, "id", 1L);
		firstMaterial = material(10L, instructor, "First", 100);
		secondMaterial = material(20L, instructor, "Second", 50);
	}

	@Test
	void aggregatesProgressViewersQuestionsAndSevenDayActivityBoundary() {
		when(weekMaterialRepository.findReportMaterials(
			eq(30L), eq(null), any(), any()
		)).thenReturn(List.of(firstMaterial, secondMaterial));
		when(memberRepository.findUserIdsByClassroomId(30L))
			.thenReturn(List.of(101L, 102L, 103L));
		when(progressService.calculateClassroomProgressSnapshot(
			30L,
			List.of(firstMaterial, secondMaterial),
			3L
		)).thenReturn(new LearningProgressService.ClassroomProgressSnapshot(
			3L,
			Map.of(10L, 75L, 20L, 50L)
		));
		var firstViewerCount = viewerCount(10L, 2L);
		var secondViewerCount = viewerCount(20L, 1L);
		when(sessionRepository.findMaterialViewerCounts(eq(30L), any(), any()))
			.thenReturn(List.of(firstViewerCount, secondViewerCount));
		var firstQuestionCount = questionCount(10L, 2, 4L, 3L);
		var secondQuestionCount = questionCount(20L, 1, 2L, 1L);
		when(qaMessageRepository.findClassroomQuestionCounts(
			eq(30L), any(), any(), eq(NOW.minus(Duration.ofDays(7)))
		)).thenReturn(List.of(
			firstQuestionCount,
			secondQuestionCount
		));
		when(sessionRepository.findLastActivityByClassroomAndStudentIds(
			30L,
			List.of(101L, 102L, 103L)
		)).thenReturn(List.of(
			new StudentLastActivity(101L, NOW.minus(Duration.ofDays(6)).minus(Duration.ofHours(23))),
			new StudentLastActivity(102L, NOW.minus(Duration.ofDays(7)).minus(Duration.ofHours(1)))
		));

		var response = service.getAnalytics(1L, UserRole.INSTRUCTOR, 30L);

		assertThat(response.learnerCount()).isEqualTo(3);
		assertThat(response.averageProgressRate()).isEqualTo(28);
		assertThat(response.aiQuestionCountLast7Days()).isEqualTo(4);
		assertThat(response.inactiveLearnerCountLast7Days()).isEqualTo(2);
		assertThat(response.lastUpdatedAt()).isEqualTo(NOW);
		assertThat(response.materials()).extracting(
			item -> item.viewerCount(),
			item -> item.viewRate(),
			item -> item.averageProgressRate()
		).containsExactly(
			org.assertj.core.groups.Tuple.tuple(2L, 67, 25),
			org.assertj.core.groups.Tuple.tuple(1L, 33, 33)
		);
		assertThat(response.questionsByPage()).extracting(
			item -> item.materialId(),
			item -> item.pageNumber(),
			item -> item.questionCount()
		).containsExactly(
			org.assertj.core.groups.Tuple.tuple(10L, 2, 4L),
			org.assertj.core.groups.Tuple.tuple(20L, 1, 2L)
		);
	}

	@Test
	void emptyClassroomReturnsZeroMetricsWithoutAggregateQueries() {
		when(weekMaterialRepository.findReportMaterials(
			eq(30L), eq(null), any(), any()
		)).thenReturn(List.of());
		when(memberRepository.findUserIdsByClassroomId(30L)).thenReturn(List.of());

		var response = service.getAnalytics(1L, UserRole.INSTRUCTOR, 30L);

		assertThat(response.learnerCount()).isZero();
		assertThat(response.averageProgressRate()).isZero();
		assertThat(response.aiQuestionCountLast7Days()).isZero();
		assertThat(response.inactiveLearnerCountLast7Days()).isZero();
		assertThat(response.materials()).isEmpty();
		assertThat(response.questionsByPage()).isEmpty();
		verify(progressService, never()).calculateClassroomProgressSnapshot(
			any(), any(), any(Long.class)
		);
		verify(sessionRepository, never()).findMaterialViewerCounts(
			any(), any(), any()
		);
		verify(qaMessageRepository, never()).findClassroomQuestionCounts(
			any(), any(), any(), any()
		);
	}

	@Test
	void hidesClassroomNotOwnedByInstructor() {
		when(classroomService.requireStrictOwner(2L, UserRole.INSTRUCTOR, 30L))
			.thenThrow(new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));

		assertThatThrownBy(() -> service.getAnalytics(
			2L,
			UserRole.INSTRUCTOR,
			30L
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLASSROOM_NOT_FOUND)
		);
	}

	private LearningMaterial material(
		Long id,
		User owner,
		String title,
		int pageCount
	) {
		LearningMaterial material = LearningMaterial.create(
			owner,
			title,
			"materials/" + id + ".pdf"
		);
		material.markReady(pageCount);
		ReflectionTestUtils.setField(material, "id", id);
		return material;
	}

	private LearningSessionRepository.MaterialViewerCount viewerCount(
		Long materialId,
		Long viewerCount
	) {
		var count = mock(LearningSessionRepository.MaterialViewerCount.class);
		when(count.getMaterialId()).thenReturn(materialId);
		when(count.getViewerCount()).thenReturn(viewerCount);
		return count;
	}

	private QaMessageRepository.ClassroomQuestionCount questionCount(
		Long materialId,
		int pageNumber,
		long questionCount,
		long recentQuestionCount
	) {
		var count = mock(QaMessageRepository.ClassroomQuestionCount.class);
		when(count.getMaterialId()).thenReturn(materialId);
		when(count.getPageNumber()).thenReturn(pageNumber);
		when(count.getQuestionCount()).thenReturn(questionCount);
		when(count.getQuestionCountLast7Days()).thenReturn(recentQuestionCount);
		return count;
	}
}
