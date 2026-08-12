package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.dto.CreateClassroomWeekRequest;
import io.edupilot.classroom.dto.ReorderClassroomWeeksRequest;
import io.edupilot.classroom.dto.UpdateClassroomWeekRequest;
import io.edupilot.classroom.dto.UpdateClassroomWeekStatusRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ClassroomWeekServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

	@Mock
	private ClassroomService classroomService;
	@Mock
	private ClassroomWeekRepository weekRepository;
	@Mock
	private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Mock
	private LearningMaterialRepository materialRepository;
	@Mock
	private ClassroomMemberRepository memberRepository;
	@Mock
	private LearningProgressService progressService;
	@Mock
	private LearningSessionRepository sessionRepository;

	private ClassroomWeekService service;
	private Classroom classroom;
	private User instructor;

	@BeforeEach
	void setUp() {
		service = new ClassroomWeekService(
			classroomService,
			weekRepository,
			weekMaterialRepository,
			materialRepository,
			memberRepository,
			progressService,
			sessionRepository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		instructor = User.create(
			"teacher@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		);
		ReflectionTestUtils.setField(instructor, "id", 1L);
		classroom = Classroom.create(
			instructor,
			"AI Basics",
			LocalDate.of(2026, 8, 1),
			LocalDate.of(2026, 8, 28),
			ClassroomColor.BLUE,
			null,
			"AAAA-BBBB"
		);
		ReflectionTestUtils.setField(classroom, "id", 30L);
	}

	@Test
	void learnerListContainsAllWeeksAndMasksUnreleasedMaterials() {
		ClassroomWeek published = week(
			4, ClassroomWeekStatus.PUBLISHED, 1, null
		);
		ClassroomWeek privateWeek = week(
			2, ClassroomWeekStatus.PRIVATE, 2, null
		);
		ClassroomWeek scheduled = week(
			3, ClassroomWeekStatus.SCHEDULED, 3, NOW.plusSeconds(60)
		);
		ClassroomWeek breakWeek = week(
			1, ClassroomWeekStatus.BREAK, 4, null
		);
		LearningMaterial publishedMaterial = material(10L, "Published");
		LearningMaterial breakMaterial = material(20L, "Break");
		when(classroomService.requireVisible(2L, UserRole.LEARNER, 30L))
			.thenReturn(classroom);
		when(weekRepository.findByClassroom_IdOrderByDisplayOrderAscIdAsc(30L))
			.thenReturn(List.of(published, privateWeek, scheduled, breakWeek));
		when(weekMaterialRepository.findByWeekIds(List.of(4L, 1L)))
			.thenReturn(List.of(
				ClassroomWeekMaterial.create(published, publishedMaterial, NOW),
				ClassroomWeekMaterial.create(breakWeek, breakMaterial, NOW)
			));
		when(memberRepository.countByClassroom_Id(30L)).thenReturn(2L);
		when(progressService.calculateClassroomProgressSnapshot(
			eq(30L), any(), eq(2L)
		)).thenReturn(new LearningProgressService.ClassroomProgressSnapshot(
			2L,
			Map.of()
		));
		when(sessionRepository.findMaterialViewerCounts(eq(30L), any(), any()))
			.thenReturn(List.of());

		var response = service.list(2L, UserRole.LEARNER, 30L);

		assertThat(response.items()).extracting(
			item -> item.weekNumber(),
			item -> item.status(),
			item -> item.displayOrder()
		).containsExactly(
			org.assertj.core.groups.Tuple.tuple(4, ClassroomWeekStatus.PUBLISHED, 1),
			org.assertj.core.groups.Tuple.tuple(2, ClassroomWeekStatus.PRIVATE, 2),
			org.assertj.core.groups.Tuple.tuple(3, ClassroomWeekStatus.SCHEDULED, 3),
			org.assertj.core.groups.Tuple.tuple(1, ClassroomWeekStatus.BREAK, 4)
		);
		assertThat(response.items()).extracting(item -> item.materials().size())
			.containsExactly(1, 0, 0, 1);
		assertThat(response.items().get(1).averageProgressRate()).isZero();
		assertThat(response.items().get(2).averageProgressRate()).isZero();
		verify(weekMaterialRepository).findByWeekIds(List.of(4L, 1L));
	}

	@Test
	void instructorListStillIncludesMaterialsForAllWeekStatuses() {
		ClassroomWeek privateWeek = week(
			1, ClassroomWeekStatus.PRIVATE, 1, null
		);
		ClassroomWeek scheduled = week(
			2, ClassroomWeekStatus.SCHEDULED, 2, NOW.plusSeconds(60)
		);
		LearningMaterial privateMaterial = material(10L, "Private");
		LearningMaterial scheduledMaterial = material(20L, "Scheduled");
		when(classroomService.requireVisible(1L, UserRole.INSTRUCTOR, 30L))
			.thenReturn(classroom);
		when(weekRepository.findByClassroom_IdOrderByDisplayOrderAscIdAsc(30L))
			.thenReturn(List.of(privateWeek, scheduled));
		when(weekMaterialRepository.findByWeekIds(List.of(1L, 2L)))
			.thenReturn(List.of(
				ClassroomWeekMaterial.create(privateWeek, privateMaterial, NOW),
				ClassroomWeekMaterial.create(scheduled, scheduledMaterial, NOW)
			));
		when(memberRepository.countByClassroom_Id(30L)).thenReturn(0L);
		when(progressService.calculateClassroomProgressSnapshot(
			eq(30L), any(), eq(0L)
		)).thenReturn(new LearningProgressService.ClassroomProgressSnapshot(
			0L,
			Map.of()
		));
		when(sessionRepository.findMaterialViewerCounts(eq(30L), any(), any()))
			.thenReturn(List.of());

		var response = service.list(1L, UserRole.INSTRUCTOR, 30L);

		assertThat(response.items()).extracting(item -> item.materials().size())
			.containsExactly(1, 1);
		verify(weekMaterialRepository).findByWeekIds(List.of(1L, 2L));
	}

	@Test
	void createRejectsWeekOutsideCalculatedRange() {
		when(classroomService.requireOwnerForUpdate(1L, UserRole.INSTRUCTOR, 30L))
			.thenReturn(classroom);

		assertThatThrownBy(() -> service.create(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomWeekRequest(5, "Week 5", null)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
		);
	}

	@Test
	void releaseAtUpdateDoesNotChangeCanonicalStatus() {
		ClassroomWeek week = week(1, NOW.plusSeconds(60));
		when(classroomService.requireOwnerForUpdate(1L, UserRole.INSTRUCTOR, 30L))
			.thenReturn(classroom);
		when(weekRepository.findForUpdate(30L, 1)).thenReturn(Optional.of(week));
		when(weekMaterialRepository.findByWeekIds(any()))
			.thenReturn(List.of());
		UpdateClassroomWeekRequest request = new UpdateClassroomWeekRequest();
		request.setReleaseAt(null);

		var response = service.update(
			1L, UserRole.INSTRUCTOR, 30L, 1, request
		);

		assertThat(response.releaseAt()).isNull();
		assertThat(response.status()).isEqualTo(ClassroomWeekStatus.SCHEDULED);
		verify(weekRepository).flush();
	}

	@Test
	void instructorCanFreelyChangeOwnedWeekStatus() {
		ClassroomWeek week = week(1, null);
		when(classroomService.requireStrictOwnerForUpdate(1L, UserRole.INSTRUCTOR, 30L))
			.thenReturn(classroom);
		when(weekRepository.findByIdForUpdate(30L, 1L))
			.thenReturn(Optional.of(week));
		var response = service.changeStatus(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			1L,
			new UpdateClassroomWeekStatusRequest(ClassroomWeekStatus.BREAK)
		);

		assertThat(response.status()).isEqualTo(ClassroomWeekStatus.BREAK);
		verify(weekRepository).flush();
	}

	@Test
	void statusUpdateHidesNonOwnedClassroomAsNotFound() {
		doThrow(new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND))
			.when(classroomService)
			.requireStrictOwnerForUpdate(9L, UserRole.INSTRUCTOR, 30L);

		assertThatThrownBy(() -> service.changeStatus(
			9L,
			UserRole.INSTRUCTOR,
			30L,
			1L,
			new UpdateClassroomWeekStatusRequest(ClassroomWeekStatus.PRIVATE)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLASSROOM_NOT_FOUND)
		);
		verify(weekRepository, never()).findByIdForUpdate(any(), any());
	}

	@Test
	void statusUpdateRejectsCompletedClassroom() {
		when(classroomService.requireStrictOwnerForUpdate(1L, UserRole.INSTRUCTOR, 30L))
			.thenReturn(classroom);
		doThrow(new BusinessException(ErrorCode.CLASSROOM_COMPLETED))
			.when(classroomService).assertWritable(classroom);

		assertThatThrownBy(() -> service.changeStatus(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			1L,
			new UpdateClassroomWeekStatusRequest(ClassroomWeekStatus.PRIVATE)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLASSROOM_COMPLETED)
		);
		verify(weekRepository, never()).findByIdForUpdate(any(), any());
	}

	@Test
	void reorderUpdatesOnlyDisplayOrderAndReturnsRequestedOrder() {
		ClassroomWeek first = week(1, null);
		ClassroomWeek second = week(2, null);
		when(classroomService.requireStrictOwnerForUpdate(1L, UserRole.INSTRUCTOR, 30L))
			.thenReturn(classroom);
		when(weekRepository.findAllForUpdateByClassroomId(30L))
			.thenReturn(List.of(first, second));
		var response = service.reorder(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new ReorderClassroomWeeksRequest(List.of(2L, 1L))
		);

		assertThat(response.items()).extracting(item -> item.weekId())
			.containsExactly(2L, 1L);
		assertThat(response.items()).extracting(item -> item.displayOrder())
			.containsExactly(1, 2);
		assertThat(first.getWeekNumber()).isEqualTo(1);
		assertThat(second.getWeekNumber()).isEqualTo(2);
		verify(weekRepository).flush();
	}

	@Test
	void reorderRejectsMissingWeekWithoutPartialUpdate() {
		assertRejectedOrder(List.of(1L));
	}

	@Test
	void reorderRejectsDuplicateWeekWithoutPartialUpdate() {
		assertRejectedOrder(List.of(1L, 1L));
	}

	@Test
	void reorderRejectsForeignWeekWithoutPartialUpdate() {
		assertRejectedOrder(List.of(1L, 99L));
	}

	private void assertRejectedOrder(List<Long> orderedWeekIds) {
		ClassroomWeek first = week(1, null);
		ClassroomWeek second = week(2, null);
		when(classroomService.requireStrictOwnerForUpdate(1L, UserRole.INSTRUCTOR, 30L))
			.thenReturn(classroom);
		when(weekRepository.findAllForUpdateByClassroomId(30L))
			.thenReturn(List.of(first, second));

		assertThatThrownBy(() -> service.reorder(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new ReorderClassroomWeeksRequest(orderedWeekIds)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
		);
		assertThat(first.getDisplayOrder()).isEqualTo(1);
		assertThat(second.getDisplayOrder()).isEqualTo(2);
		verify(weekRepository, never()).flush();
	}

	private ClassroomWeek week(int number, Instant releaseAt) {
		ClassroomWeekStatus status = releaseAt == null || !releaseAt.isAfter(NOW)
			? ClassroomWeekStatus.PUBLISHED
			: ClassroomWeekStatus.SCHEDULED;
		return week(number, status, number, releaseAt);
	}

	private ClassroomWeek week(
		int number,
		ClassroomWeekStatus status,
		int displayOrder,
		Instant releaseAt
	) {
		ClassroomWeek week = ClassroomWeek.create(
			classroom, number, "Week " + number, releaseAt, status, displayOrder
		);
		ReflectionTestUtils.setField(week, "id", (long) number);
		return week;
	}

	private LearningMaterial material(Long id, String title) {
		LearningMaterial material = LearningMaterial.create(
			instructor, title, "materials/" + id + ".pdf"
		);
		material.markReady(10);
		ReflectionTestUtils.setField(material, "id", id);
		return material;
	}
}
