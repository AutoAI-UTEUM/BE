package io.edupilot.global.response;

public record ApiResponse<T>(
	boolean success,
	T data,
	String message
) {

	private static final String DEFAULT_SUCCESS_MESSAGE = "요청이 성공했습니다.";

	public static <T> ApiResponse<T> success(T data) {
		return success(data, DEFAULT_SUCCESS_MESSAGE);
	}

	public static <T> ApiResponse<T> success(T data, String message) {
		return new ApiResponse<>(true, data, message);
	}
}
