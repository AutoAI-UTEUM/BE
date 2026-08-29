package io.edupilot.admin.dto;

import java.util.List;

public record AdminUserListResponse(
	List<AdminUserResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
}
