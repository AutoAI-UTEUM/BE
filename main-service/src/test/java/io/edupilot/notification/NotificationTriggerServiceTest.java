package io.edupilot.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomJoinRequest;
import io.edupilot.classroom.ClassroomNotice;
import io.edupilot.classroom.ClassroomNoticeRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class NotificationTriggerServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-14T03:00:00Z");

	@Mock
	private NotificationBulkRepository bulkRepository;
	@Mock
	private ClassroomNoticeRepository noticeRepository;

	private NotificationTriggerService service;
	private Classroom classroom;
	private User learner;

	@BeforeEach
	void setUp() {
		service = new NotificationTriggerService(
			bulkRepository,
			noticeRepository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		User instructor = user(1L, "teacher@example.com", "Teacher", UserRole.INSTRUCTOR);
		learner = user(2L, "learner@example.com", "Learner", UserRole.LEARNER);
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
	void materialAndJoinTriggersUseExpectedRecipientsAndLinks() {
		service.materialUploaded(30L, 10L, "Week 1 PDF");

		verify(bulkRepository).insertForClassroomMembers(
			eq(30L),
			eq(NotificationType.MATERIAL_UPLOADED),
			any(),
			eq("Week 1 PDF"),
			org.mockito.ArgumentMatchers.argThat(link ->
				link.get("classroomId").equals(30L)
					&& link.get("materialId").equals(10L)
			),
			eq(NOW)
		);

		ClassroomJoinRequest request = ClassroomJoinRequest.create(
			classroom, learner, NOW
		);
		ReflectionTestUtils.setField(request, "id", 50L);
		service.joinRequestReceived(request);
		verify(bulkRepository).insertForUser(
			eq(1L),
			eq(NotificationType.JOIN_REQUEST_RECEIVED),
			any(),
			eq("Learner"),
			any(),
			eq(NOW)
		);

		request.approve(NOW);
		service.joinRequestProcessed(request);
		verify(bulkRepository).insertForUser(
			eq(2L),
			eq(NotificationType.JOIN_REQUEST_PROCESSED),
			eq("강의실 입장 요청이 승인되었습니다"),
			eq("AI Basics"),
			any(),
			eq(NOW)
		);
	}

	@Test
	void rejectedJoinRequestNotifiesStudentWithProcessedType() {
		ClassroomJoinRequest request = ClassroomJoinRequest.create(
			classroom, learner, NOW.minusSeconds(60)
		);
		ReflectionTestUtils.setField(request, "id", 51L);
		request.reject(NOW);

		service.joinRequestProcessed(request);

		verify(bulkRepository).insertForUser(
			eq(2L),
			eq(NotificationType.JOIN_REQUEST_PROCESSED),
			eq("강의실 입장 요청이 거절되었습니다"),
			eq("AI Basics"),
			any(),
			eq(NOW)
		);
	}

	@Test
	void scheduledNoticeIsPublishedOnceAfterDueTime() {
		ClassroomNotice notice = notice(NOW.minusSeconds(1));
		when(noticeRepository.findNotificationCandidates(
			eq(NOW), any(Pageable.class)
		)).thenReturn(List.of(notice));

		service.publishDueNotices(NOW, 100);
		service.publishDueNotices(NOW, 100);

		verify(bulkRepository, times(1)).insertForClassroomMembers(
			eq(30L),
			eq(NotificationType.NOTICE_PUBLISHED),
			eq("Notice"),
			eq("Content"),
			any(),
			eq(NOW)
		);
		assertThat(notice.getNotificationSentAt()).isEqualTo(NOW);
	}

	@Test
	void futureNoticeDoesNotPublish() {
		service.noticePublished(notice(NOW.plusSeconds(1)), NOW);

		verify(bulkRepository, never()).insertForClassroomMembers(
			any(), any(), any(), any(), any(), any()
		);
	}

	private ClassroomNotice notice(Instant publishAt) {
		ClassroomNotice notice = ClassroomNotice.create(
			classroom,
			"Notice",
			"Content",
			2,
			publishAt,
			NOW.minusSeconds(60)
		);
		ReflectionTestUtils.setField(notice, "id", 70L);
		return notice;
	}

	private User user(Long id, String email, String name, UserRole role) {
		User user = User.create(email, "hash", name, role);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
