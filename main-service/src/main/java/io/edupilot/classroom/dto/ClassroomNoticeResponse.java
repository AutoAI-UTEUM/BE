package io.edupilot.classroom.dto;

import java.time.Instant;

import io.edupilot.classroom.ClassroomNotice;

public record ClassroomNoticeResponse(
	Long noticeId,
	Long classroomId,
	String title,
	String content,
	Instant publishedAt,
	Instant createdAt,
	Instant updatedAt
) {
	public static ClassroomNoticeResponse from(ClassroomNotice notice) {
		return new ClassroomNoticeResponse(
			notice.getId(),
			notice.getClassroom().getId(),
			notice.getTitle(),
			notice.getContent(),
			notice.getPublishedAt(),
			notice.getCreatedAt(),
			notice.getUpdatedAt()
		);
	}
}
