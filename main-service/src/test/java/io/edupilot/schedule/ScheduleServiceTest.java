package io.edupilot.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomNotice;
import io.edupilot.classroom.ClassroomNoticeRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

	private static final Instant BOUNDARY = Instant.parse("2026-08-02T00:00:00Z");

	@Mock
	private ClassroomService classroomService;
	@Mock
	private ClassroomRepository classroomRepository;
	@Mock
	private ClassroomWeekRepository weekRepository;
	@Mock
	private ClassroomNoticeRepository noticeRepository;

	private ScheduleService service;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		service = new ScheduleService(
			classroomService,
			classroomRepository,
			weekRepository,
			noticeRepository
		);
		User instructor = User.create(
			"teacher@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		);
		ReflectionTestUtils.setField(instructor, "id", 1L);
		classroom = Classroom.create(
			instructor,
			"AI Basics",
			LocalDate.of(2026, 8, 1),
			LocalDate.of(2026, 8, 31),
			ClassroomColor.BLUE,
			null,
			"AAAA-BBBB"
		);
		ReflectionTestUtils.setField(classroom, "id", 30L);
	}

	@Test
	void usesInclusiveUtcDateRangeAndStableMergedOrdering() {
		ClassroomWeek week = ClassroomWeek.create(
			classroom, 2, "Models", BOUNDARY
		);
		ReflectionTestUtils.setField(week, "id", 40L);
		ReflectionTestUtils.setField(week, "createdAt", BOUNDARY.minusSeconds(60));
		ClassroomNotice notice = ClassroomNotice.create(
			classroom, "Assignment", "Content", BOUNDARY
		);
		ReflectionTestUtils.setField(notice, "id", 70L);
		when(classroomRepository.findAllVisibleByUserId(2L))
			.thenReturn(List.of(classroom));
		when(weekRepository.findScheduleWeeks(
			List.of(30L),
			BOUNDARY,
			Instant.parse("2026-08-03T00:00:00Z")
		)).thenReturn(List.of(week));
		when(noticeRepository.findScheduleNotices(
			List.of(30L),
			BOUNDARY,
			Instant.parse("2026-08-03T00:00:00Z")
		)).thenReturn(List.of(notice));

		var response = service.list(
			2L,
			UserRole.LEARNER,
			LocalDate.of(2026, 8, 2),
			LocalDate.of(2026, 8, 2),
			null
		);

		assertThat(response.items())
			.extracting(item -> item.scheduleId())
			.containsExactly("NOTICE-70", "WEEK-40");
		assertThat(response.items().get(1).title()).isEqualTo("2주차 공개: Models");
	}

	@Test
	void classroomFilterIsValidatedThroughVisibilityCheck() {
		when(classroomService.requireVisible(2L, UserRole.LEARNER, 30L))
			.thenReturn(classroom);
		when(weekRepository.findScheduleWeeks(
			List.of(30L),
			BOUNDARY,
			Instant.parse("2026-08-03T00:00:00Z")
		)).thenReturn(List.of());
		when(noticeRepository.findScheduleNotices(
			List.of(30L),
			BOUNDARY,
			Instant.parse("2026-08-03T00:00:00Z")
		)).thenReturn(List.of());

		service.list(
			2L,
			UserRole.LEARNER,
			LocalDate.of(2026, 8, 2),
			LocalDate.of(2026, 8, 2),
			30L
		);

		verify(classroomService).requireVisible(2L, UserRole.LEARNER, 30L);
	}

	@Test
	void rejectsReversedRange() {
		assertThatThrownBy(() -> service.list(
			2L,
			UserRole.LEARNER,
			LocalDate.of(2026, 8, 3),
			LocalDate.of(2026, 8, 2),
			null
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
		);
	}
}
