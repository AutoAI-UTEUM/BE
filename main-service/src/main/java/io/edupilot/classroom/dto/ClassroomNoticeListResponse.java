package io.edupilot.classroom.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.classroom.ClassroomNotice;

public record ClassroomNoticeListResponse(
	List<ClassroomNoticeResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static ClassroomNoticeListResponse from(Page<ClassroomNotice> notices) {
		return new ClassroomNoticeListResponse(
			notices.getContent().stream()
				.map(ClassroomNoticeResponse::from)
				.toList(),
			notices.getNumber(),
			notices.getSize(),
			notices.getTotalElements(),
			notices.getTotalPages()
		);
	}
}
