package io.edupilot.memory;

import java.util.List;

public record MemoryWrite(
	List<Long> candidateIds
) {
}
