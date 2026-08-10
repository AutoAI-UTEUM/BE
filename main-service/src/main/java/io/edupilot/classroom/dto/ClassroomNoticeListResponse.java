package io.edupilot.classroom.dto;

import java.time.Instant;
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
	public static ClassroomNoticeListResponse from(
		Page<ClassroomNotice> notices,
		Instant now
	) {
		return new ClassroomNoticeListResponse(
			notices.getContent().stream()
				.map(notice -> ClassroomNoticeResponse.from(notice, now))
				.toList(),
			notices.getNumber(),
			notices.getSize(),
			notices.getTotalElements(),
			notices.getTotalPages()
		);
	}
}
