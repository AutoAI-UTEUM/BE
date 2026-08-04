package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.dto.CreateClassroomWeekRequest;
import io.edupilot.classroom.dto.UpdateClassroomWeekRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterialRepository;
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

	private ClassroomWeekService service;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		service = new ClassroomWeekService(
			classroomService,
			weekRepository,
			weekMaterialRepository,
			materialRepository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		User instructor = User.create(
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
	void learnerListContainsOnlyReleasedWeeks() {
		ClassroomWeek released = week(1, null);
		ClassroomWeek scheduled = week(2, NOW.plusSeconds(60));
		when(classroomService.requireVisible(2L, UserRole.LEARNER, 30L))
			.thenReturn(classroom);
		when(weekRepository.findByClassroom_IdOrderByDisplayOrderAscIdAsc(30L))
			.thenReturn(List.of(released, scheduled));
		when(weekMaterialRepository.findByWeek_IdOrderByAddedAtAscIdAsc(any()))
			.thenReturn(List.of());

		var response = service.list(2L, UserRole.LEARNER, 30L);

		assertThat(response.items()).singleElement()
			.satisfies(item -> {
				assertThat(item.weekNumber()).isEqualTo(1);
				assertThat(item.status()).isEqualTo(ClassroomWeekStatus.PUBLISHED);
			});
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
		when(weekMaterialRepository.findByWeek_IdOrderByAddedAtAscIdAsc(1L))
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

	private ClassroomWeek week(int number, Instant releaseAt) {
		ClassroomWeekStatus status = releaseAt == null || !releaseAt.isAfter(NOW)
			? ClassroomWeekStatus.PUBLISHED
			: ClassroomWeekStatus.SCHEDULED;
		ClassroomWeek week = ClassroomWeek.create(
			classroom, number, "Week " + number, releaseAt, status, number
		);
		ReflectionTestUtils.setField(week, "id", (long) number);
		return week;
	}
}
