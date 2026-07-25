package io.edupilot.material;

@FunctionalInterface
public interface MaterialDeletionGuard {

	void assertDeletable(Long materialId);
}
