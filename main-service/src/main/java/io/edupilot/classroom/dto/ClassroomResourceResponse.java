package io.edupilot.classroom.dto;

import java.time.Instant;

import io.edupilot.classroom.ClassroomResource;
import io.edupilot.classroom.ClassroomResourceType;

public record ClassroomResourceResponse(
	Long resourceId,
	ClassroomResourceType type,
	String title,
	Integer weekNumber,
	String fileName,
	String contentType,
	Long sizeBytes,
	String url,
	Instant createdAt
) {
	public static ClassroomResourceResponse from(ClassroomResource resource) {
		return new ClassroomResourceResponse(
			resource.getId(),
			resource.getType(),
			resource.getTitle(),
			resource.getWeekNumber(),
			resource.getFileName(),
			resource.getContentType(),
			resource.getSizeBytes(),
			resource.getUrl(),
			resource.getCreatedAt()
		);
	}
}
