package io.edupilot.admin.xai;

public class XaiManagementClientException extends RuntimeException {

	private final XaiManagementFailureType failureType;

	public XaiManagementClientException(
		XaiManagementFailureType failureType
	) {
		super("xAI Management API request failed: " + failureType);
		this.failureType = failureType;
	}

	public XaiManagementFailureType failureType() {
		return failureType;
	}
}
