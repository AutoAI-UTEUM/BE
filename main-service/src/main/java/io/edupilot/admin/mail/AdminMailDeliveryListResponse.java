package io.edupilot.admin.mail;

import java.util.List;

public record AdminMailDeliveryListResponse(
	List<AdminMailDeliveryResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
}
