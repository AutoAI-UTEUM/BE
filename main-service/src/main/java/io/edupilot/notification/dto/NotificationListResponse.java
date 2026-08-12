package io.edupilot.notification.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.notification.Notification;

public record NotificationListResponse(
	List<NotificationResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static NotificationListResponse from(Page<Notification> notifications) {
		return new NotificationListResponse(
			notifications.getContent().stream()
				.map(NotificationResponse::from)
				.toList(),
			notifications.getNumber(),
			notifications.getSize(),
			notifications.getTotalElements(),
			notifications.getTotalPages()
		);
	}
}
