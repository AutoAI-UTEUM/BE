package io.edupilot.classroom;

import org.springframework.core.io.Resource;

public record ClassroomResourceFile(
	Resource resource,
	String fileName,
	String contentType,
	boolean inline
) {
}
