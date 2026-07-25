package io.edupilot.session.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.session.LearningSession;

public record SessionListResponse(
	List<SessionListItemResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static SessionListResponse from(Page<LearningSession> sessions) {
		return new SessionListResponse(
			sessions.getContent().stream()
				.map(SessionListItemResponse::from)
				.toList(),
			sessions.getNumber(),
			sessions.getSize(),
			sessions.getTotalElements(),
			sessions.getTotalPages()
		);
	}
}
