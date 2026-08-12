package io.edupilot.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-14T03:00:00Z");

	@Mock
	private NotificationRepository notificationRepository;

	private NotificationService service;
	private Notification notification;

	@BeforeEach
	void setUp() {
		service = new NotificationService(
			notificationRepository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		User user = User.create("user@example.com", "hash", "Learner");
		ReflectionTestUtils.setField(user, "id", 1L);
		notification = Notification.create(
			user,
			NotificationType.MATERIAL_UPLOADED,
			"New material",
			"Week 1 PDF",
			Map.of("classroomId", 30L, "materialId", 10L),
			NOW.minusSeconds(10)
		);
		ReflectionTestUtils.setField(notification, "id", 100L);
	}

	@Test
	void listsNewestFirstWithPageContract() {
		when(notificationRepository.findByUser_Id(
			org.mockito.ArgumentMatchers.eq(1L),
			any(Pageable.class)
		)).thenReturn(new PageImpl<>(List.of(notification)));

		var response = service.list(1L, 0, 20);

		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.notificationId()).isEqualTo(100L);
			assertThat(item.link()).containsEntry("classroomId", 30L);
		});
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(notificationRepository).findByUser_Id(
			org.mockito.ArgumentMatchers.eq(1L),
			pageable.capture()
		);
		assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending())
			.isTrue();
		assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending())
			.isTrue();
	}

	@Test
	void readIsIdempotentAndDeleteRequiresOwnership() {
		when(notificationRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(notification));

		assertThat(service.read(1L, 100L).readAt()).isEqualTo(NOW);
		assertThat(service.read(1L, 100L).readAt()).isEqualTo(NOW);
		service.delete(1L, 100L);
		verify(notificationRepository).delete(notification);

		when(notificationRepository.findByIdAndUser_Id(100L, 2L))
			.thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.read(2L, 100L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND)
			);
		assertThatThrownBy(() -> service.delete(2L, 100L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND)
			);
	}
}
