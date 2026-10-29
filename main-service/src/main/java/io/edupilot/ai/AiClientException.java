package io.edupilot.ai;

import io.edupilot.ai.dto.AiUsage;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

public class AiClientException extends BusinessException {

	private final AiFailureCategory category;
	private final boolean retryable;
	private final String upstreamCode;
	private final AiUsage usage;

	public AiClientException(ErrorCode errorCode) {
		this(errorCode, false, null);
	}

	public AiClientException(ErrorCode errorCode, Throwable cause) {
		this(errorCode, false, cause);
	}

	public AiClientException(
		ErrorCode errorCode,
		boolean retryable,
		Throwable cause
	) {
		this(errorCode, categoryFor(errorCode), retryable, null, cause);
	}

	public AiClientException(
		ErrorCode errorCode,
		AiFailureCategory category,
		boolean retryable,
		Throwable cause
	) {
		this(errorCode, category, retryable, null, cause);
	}

	public AiClientException(
		ErrorCode errorCode,
		AiFailureCategory category,
		boolean retryable,
		String upstreamCode,
		Throwable cause
	) {
		this(errorCode, category, retryable, upstreamCode, cause, null);
	}

	private AiClientException(
		ErrorCode errorCode,
		AiFailureCategory category,
		boolean retryable,
		String upstreamCode,
		Throwable cause,
		AiUsage usage
	) {
		super(errorCode);
		this.category = category;
		this.retryable = retryable;
		this.upstreamCode = upstreamCode;
		this.usage = usage;
		if (cause != null) {
			initCause(cause);
		}
	}

	public AiFailureCategory category() {
		return category;
	}

	public boolean retryable() {
		return retryable;
	}

	public String upstreamCode() {
		return upstreamCode;
	}

	public AiUsage usage() {
		return usage;
	}

	public AiClientException withUsage(AiUsage knownUsage) {
		return knownUsage == null ? this : new AiClientException(
			errorCode(), category, retryable, upstreamCode, getCause(), knownUsage);
	}

	private static AiFailureCategory categoryFor(ErrorCode errorCode) {
		return switch (errorCode) {
			case AI_SERVICE_TIMEOUT -> AiFailureCategory.TIMEOUT;
			case AI_RESPONSE_INVALID -> AiFailureCategory.SCHEMA;
			case AI_POLICY_REJECTED -> AiFailureCategory.POLICY;
			default -> AiFailureCategory.INTERNAL;
		};
	}
}
