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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.dto.CreateClassroomNoticeRequest;
import io.edupilot.classroom.dto.UpdateClassroomNoticeRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ClassroomNoticeServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-02T01:00:00Z");

	@Mock
	private ClassroomService classroomService;
	@Mock
	private ClassroomNoticeRepository noticeRepository;

	private ClassroomNoticeService service;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		service = new ClassroomNoticeService(
			classroomService,
			noticeRepository,
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
			LocalDate.of(2026, 8, 31),
			ClassroomColor.BLUE,
			null,
			"AAAA-BBBB"
		);
		ReflectionTestUtils.setField(classroom, "id", 30L);
	}

	@Test
	void createsImmediateNoticeAndTrimsText() {
		when(classroomService.requireOwnerForUpdate(
			1L, UserRole.INSTRUCTOR, 30L
		)).thenReturn(classroom);
		when(noticeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			ClassroomNotice notice = invocation.getArgument(0);
			ReflectionTestUtils.setField(notice, "id", 70L);
			ReflectionTestUtils.setField(notice, "createdAt", NOW);
			ReflectionTestUtils.setField(notice, "updatedAt", NOW);
			return notice;
		});

		var response = service.create(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomNoticeRequest(" Notice ", " Content ")
		);

		assertThat(response.noticeId()).isEqualTo(70L);
		assertThat(response.title()).isEqualTo("Notice");
		assertThat(response.content()).isEqualTo("Content");
		assertThat(response.publishedAt()).isEqualTo(NOW);
		assertThat(response.weekNumber()).isNull();
		assertThat(response.publishAt()).isNull();
		assertThat(response.published()).isTrue();
		verify(classroomService).assertWritable(classroom);
	}

	@Test
	void createsWeekNoticeAndRejectsWeekOutsideClassroomRange() {
		Instant publishAt = NOW.plusSeconds(3600);
		when(classroomService.requireOwnerForUpdate(
			1L, UserRole.INSTRUCTOR, 30L
		)).thenReturn(classroom);
		when(noticeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			ClassroomNotice notice = invocation.getArgument(0);
			ReflectionTestUtils.setField(notice, "id", 70L);
			ReflectionTestUtils.setField(notice, "createdAt", NOW);
			ReflectionTestUtils.setField(notice, "updatedAt", NOW);
			return notice;
		});

		var response = service.create(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomNoticeRequest(
				"Week notice",
				"Content",
				5,
				publishAt
			)
		);

		assertThat(response.weekNumber()).isEqualTo(5);
		assertThat(response.publishAt()).isEqualTo(publishAt);
		assertThat(response.published()).isFalse();
		var alreadyPublished = service.create(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomNoticeRequest(
				"Past notice",
				"Content",
				null,
				NOW.minusSeconds(1)
			)
		);
		assertThat(alreadyPublished.published()).isTrue();
		assertError(() -> service.create(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomNoticeRequest("Invalid", "Content", 6, null)
		), ErrorCode.VALIDATION_FAILED);
		assertError(() -> service.create(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomNoticeRequest("Invalid", "Content", 0, null)
		), ErrorCode.VALIDATION_FAILED);
	}

	@Test
	void patchRequiresAFieldAndPreservesPublishedAt() {
		ClassroomNotice notice = notice();
		when(classroomService.requireOwnerForUpdate(
			1L, UserRole.INSTRUCTOR, 30L
		)).thenReturn(classroom);
		when(noticeRepository.findForUpdate(30L, 70L))
			.thenReturn(Optional.of(notice));
		UpdateClassroomNoticeRequest request = new UpdateClassroomNoticeRequest();
		request.setTitle(" Updated ");
		request.setWeekNumber(null);
		request.setPublishAt(NOW.plusSeconds(3600));

		var response = service.update(
			1L, UserRole.INSTRUCTOR, 30L, 70L, request
		);

		assertThat(response.title()).isEqualTo("Updated");
		assertThat(response.content()).isEqualTo("Content");
		assertThat(response.publishedAt()).isEqualTo(NOW);
		assertThat(response.weekNumber()).isNull();
		assertThat(response.publishAt()).isEqualTo(NOW.plusSeconds(3600));
		assertThat(response.published()).isFalse();

		assertError(() -> service.update(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			70L,
			new UpdateClassroomNoticeRequest()
		), ErrorCode.VALIDATION_FAILED);
		UpdateClassroomNoticeRequest invalidWeek = new UpdateClassroomNoticeRequest();
		invalidWeek.setWeekNumber(6);
		assertError(() -> service.update(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			70L,
			invalidWeek
		), ErrorCode.VALIDATION_FAILED);

		UpdateClassroomNoticeRequest publishImmediately = new UpdateClassroomNoticeRequest();
		publishImmediately.setPublishAt(null);
		var immediateResponse = service.update(
			1L, UserRole.INSTRUCTOR, 30L, 70L, publishImmediately
		);
		assertThat(immediateResponse.publishAt()).isNull();
		assertThat(immediateResponse.published()).isTrue();
	}

	@Test
	void listsReservedNoticeForInstructorAndPublishesByClockForLearner() {
		Instant publishAt = NOW.plusSeconds(3600);
		ClassroomNotice reserved = ClassroomNotice.create(
			classroom,
			"Reserved",
			"Content",
			2,
			publishAt,
			NOW
		);
		ReflectionTestUtils.setField(reserved, "id", 70L);
		ReflectionTestUtils.setField(reserved, "createdAt", NOW);
		ReflectionTestUtils.setField(reserved, "updatedAt", NOW);
		var page = new PageImpl<>(List.of(reserved));
		when(classroomService.requireVisible(
			1L, UserRole.INSTRUCTOR, 30L
		)).thenReturn(classroom);
		when(classroomService.requireVisible(
			2L, UserRole.LEARNER, 30L
		)).thenReturn(classroom);
		when(noticeRepository.findByClassroom_Id(
			org.mockito.ArgumentMatchers.eq(30L),
			any(Pageable.class)
		)).thenReturn(page);
		when(noticeRepository.findPublishedByClassroomId(
			org.mockito.ArgumentMatchers.eq(30L),
			org.mockito.ArgumentMatchers.eq(NOW),
			any(Pageable.class)
		)).thenReturn(new PageImpl<>(List.of()));
		when(noticeRepository.findPublishedByClassroomId(
			org.mockito.ArgumentMatchers.eq(30L),
			org.mockito.ArgumentMatchers.eq(publishAt),
			any(Pageable.class)
		)).thenReturn(page);

		assertThat(service.list(
			1L, UserRole.INSTRUCTOR, 30L, 0, 20
		).items()).singleElement()
			.satisfies(item -> assertThat(item.published()).isFalse());
		assertThat(service.list(
			2L, UserRole.LEARNER, 30L, 0, 20
		).items()).isEmpty();
		ClassroomNoticeService afterPublishService = new ClassroomNoticeService(
			classroomService,
			noticeRepository,
			Clock.fixed(publishAt, ZoneOffset.UTC)
		);
		assertThat(afterPublishService.list(
			2L, UserRole.LEARNER, 30L, 0, 20
		).items()).singleElement()
			.satisfies(item -> assertThat(item.published()).isTrue());
	}

	@Test
	void deletePhysicallyRemovesOwnedNotice() {
		ClassroomNotice notice = notice();
		when(classroomService.requireOwnerForUpdate(
			1L, UserRole.INSTRUCTOR, 30L
		)).thenReturn(classroom);
		when(noticeRepository.findForUpdate(30L, 70L))
			.thenReturn(Optional.of(notice));

		service.delete(1L, UserRole.INSTRUCTOR, 30L, 70L);

		verify(noticeRepository).delete(notice);
	}

	private ClassroomNotice notice() {
		ClassroomNotice notice = ClassroomNotice.create(
			classroom, "Notice", "Content", NOW
		);
		ReflectionTestUtils.setField(notice, "id", 70L);
		ReflectionTestUtils.setField(notice, "createdAt", NOW);
		ReflectionTestUtils.setField(notice, "updatedAt", NOW);
		return notice;
	}

	private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(
			BusinessException.class,
			exception -> assertThat(exception.errorCode()).isEqualTo(code)
		);
	}
}
