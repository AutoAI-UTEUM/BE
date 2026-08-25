package io.edupilot.classroom.dto;

import java.time.Instant;

import io.edupilot.classroom.ClassroomNotice;

public record ClassroomNoticeResponse(
	Long noticeId,
	Long classroomId,
	Integer weekNumber,
	String title,
	String content,
	Instant publishedAt,
	Instant publishAt,
	boolean published,
	Instant createdAt,
	Instant updatedAt
) {
	public static ClassroomNoticeResponse from(
		ClassroomNotice notice,
		Instant now
	) {
		return new ClassroomNoticeResponse(
			notice.getId(),
			notice.getClassroom().getId(),
			notice.getWeekNumber(),
			notice.getTitle(),
			notice.getContent(),
			notice.getPublishedAt(),
			notice.getPublishAt(),
			notice.isPublished(now),
			notice.getCreatedAt(),
			notice.getUpdatedAt()
		);
	}
}
