package io.edupilot.session;

import java.util.Map;

public record TurnSnapshot(
	Map<String, Object> session,
	Map<String, Object> context,
	Long materialId,
	boolean xaiFileAttached,
	Map<String, Object> capabilities
) {
	public TurnSnapshot(
		Map<String, Object> session,
		Map<String, Object> context,
		Long materialId,
		boolean xaiFileAttached
	) {
		this(session, context, materialId, xaiFileAttached, null);
	}
}
