package io.edupilot.notification.dto;

import java.time.Instant;
import java.util.Map;

import io.edupilot.notification.Notification;
import io.edupilot.notification.NotificationType;

public record NotificationResponse(
	Long notificationId,
	NotificationType type,
	String title,
	String body,
	Map<String, Object> link,
	Instant readAt,
	Instant createdAt
) {
	public static NotificationResponse from(Notification notification) {
		return new NotificationResponse(
			notification.getId(),
			notification.getType(),
			notification.getTitle(),
			notification.getBody(),
			notification.getLink(),
			notification.getReadAt(),
			notification.getCreatedAt()
		);
	}
}
