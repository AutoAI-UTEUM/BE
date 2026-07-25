package io.edupilot.material;

import org.springframework.core.io.Resource;

public record MaterialFile(
	Long materialId,
	Resource resource
) {
}
