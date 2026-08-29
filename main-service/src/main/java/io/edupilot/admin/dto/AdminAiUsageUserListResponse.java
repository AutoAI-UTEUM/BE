package io.edupilot.admin.dto;

import java.util.List;

public record AdminAiUsageUserListResponse(
	List<AdminAiUsageUserResponse> items
) {
}
