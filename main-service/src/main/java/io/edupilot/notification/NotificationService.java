package io.edupilot.notification;

import java.time.Clock;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.notification.dto.NotificationListResponse;
import io.edupilot.notification.dto.NotificationResponse;

@Service
public class NotificationService {

	private final NotificationRepository notificationRepository;
	private final Clock clock;

	public NotificationService(
		NotificationRepository notificationRepository,
		Clock clock
	) {
		this.notificationRepository = notificationRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public NotificationListResponse list(Long userId, int page, int size) {
		PageRequest pageable = PageRequest.of(
			page,
			size,
			Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
		);
		return NotificationListResponse.from(
			notificationRepository.findByUser_Id(userId, pageable)
		);
	}

	@Transactional
	public NotificationResponse read(Long userId, Long notificationId) {
		Notification notification = owned(userId, notificationId);
		notification.markRead(clock.instant());
		return NotificationResponse.from(notification);
	}

	@Transactional
	public void delete(Long userId, Long notificationId) {
		notificationRepository.delete(owned(userId, notificationId));
	}

	private Notification owned(Long userId, Long notificationId) {
		return notificationRepository.findByIdAndUser_Id(notificationId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}
}
