package io.edupilot.session.dto;

import java.util.List;

public record MessageListResponse(
	List<MessageResponse> items,
	String nextCursor,
	boolean hasMore
) {
}
