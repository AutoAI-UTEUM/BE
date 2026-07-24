package io.edupilot.global.response;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
	boolean success,
	ApiError error,
	String traceId,
	Instant timestamp
) {

	public static ErrorResponse failure(
		String code,
		String message,
		List<ErrorDetail> details,
		String traceId
	) {
		return new ErrorResponse(
			false,
			new ApiError(code, message, details),
			traceId,
			Instant.now()
		);
	}
}
