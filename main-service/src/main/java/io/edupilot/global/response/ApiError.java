package io.edupilot.global.response;

import java.util.List;

public record ApiError(
	String code,
	String message,
	List<ErrorDetail> details
) {

	public ApiError {
		details = List.copyOf(details);
	}
}
