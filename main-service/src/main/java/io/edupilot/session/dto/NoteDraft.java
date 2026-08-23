package io.edupilot.session.dto;

public record NoteDraft(
	String title,
	String content
) {

	public static NoteDraft from(io.edupilot.ai.dto.NoteDraft draft) {
		return draft == null
			? null
			: new NoteDraft(draft.title(), draft.content());
	}
}
