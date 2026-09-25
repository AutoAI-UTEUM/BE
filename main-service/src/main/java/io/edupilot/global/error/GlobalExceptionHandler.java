package io.edupilot.global.error;

import java.util.Comparator;
import java.util.List;

import jakarta.validation.ConstraintViolationException;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.edupilot.auth.RefreshTokenCookie;
import io.edupilot.auth.LoginRateLimitedException;
import io.edupilot.global.response.ErrorDetail;
import io.edupilot.global.response.ErrorResponse;
import io.edupilot.global.security.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(
		MethodArgumentNotValidException exception,
		HttpServletRequest request
	) {
		List<ErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
			.map(error -> new ErrorDetail(error.getField(), error.getDefaultMessage()))
			.sorted(Comparator.comparing(ErrorDetail::field))
			.toList();

		return errorResponse(ErrorCode.VALIDATION_FAILED, details, request);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(
		ConstraintViolationException exception,
		HttpServletRequest request
	) {
		List<ErrorDetail> details = exception.getConstraintViolations().stream()
			.map(violation -> new ErrorDetail(
				violation.getPropertyPath().toString(),
				violation.getMessage()
			))
			.sorted(Comparator.comparing(ErrorDetail::field))
			.toList();

		return errorResponse(ErrorCode.VALIDATION_FAILED, details, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleMalformedRequest(HttpServletRequest request) {
		return errorResponse(ErrorCode.MALFORMED_REQUEST, List.of(), request);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpServletRequest request) {
		return errorResponse(ErrorCode.UNSUPPORTED_MEDIA_TYPE, List.of(), request);
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleMaxUploadSize(
		HttpServletRequest request
	) {
		return errorResponse(ErrorCode.FILE_TOO_LARGE, List.of(), request);
	}

	@ExceptionHandler({
		MissingServletRequestPartException.class,
		MissingServletRequestParameterException.class,
		MethodArgumentTypeMismatchException.class
	})
	public ResponseEntity<ErrorResponse> handleMissingRequestValue(
		HttpServletRequest request
	) {
		return errorResponse(ErrorCode.VALIDATION_FAILED, List.of(), request);
	}

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(
		BusinessException exception,
		HttpServletRequest request
	) {
		ResponseEntity<ErrorResponse> response = errorResponse(
			exception.errorCode(),
			exception.clientMessage(),
			List.of(),
			request
		);
		if (exception instanceof LoginRateLimitedException limited) {
			return ResponseEntity.status(response.getStatusCode())
				.header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()))
				.body(response.getBody());
		}
		if (!shouldExpireRefreshCookie(exception.errorCode(), request)) {
			return response;
		}
		return ResponseEntity.status(response.getStatusCode())
			.header(HttpHeaders.SET_COOKIE, RefreshTokenCookie.expired().toString())
			.body(response.getBody());
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResourceFound(HttpServletRequest request) {
		return errorResponse(ErrorCode.RESOURCE_NOT_FOUND, List.of(), request);
	}

	@ExceptionHandler(AsyncRequestNotUsableException.class)
	public ResponseEntity<ErrorResponse> handleAsyncRequestNotUsable(
		AsyncRequestNotUsableException exception,
		HttpServletRequest request
	) {
		logClientDisconnect(request);
		return null;
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpectedException(
		Exception exception,
		HttpServletRequest request
	) {
		if (hasClientAbortCause(exception)) {
			logClientDisconnect(request);
			return null;
		}
		String traceId = traceId(request);
		log.atError()
			.addKeyValue(
				"errorCode",
				ErrorCode.INTERNAL_SERVER_ERROR.code()
			)
			.setCause(exception)
			.log("Unhandled exception");
		return errorResponse(ErrorCode.INTERNAL_SERVER_ERROR, List.of(), traceId);
	}

	private ResponseEntity<ErrorResponse> errorResponse(
		ErrorCode errorCode,
		List<ErrorDetail> details,
		HttpServletRequest request
	) {
		return errorResponse(errorCode, errorCode.message(), details, request);
	}

	private ResponseEntity<ErrorResponse> errorResponse(
		ErrorCode errorCode,
		String message,
		List<ErrorDetail> details,
		HttpServletRequest request
	) {
		return errorResponse(errorCode, message, details, traceId(request));
	}

	private ResponseEntity<ErrorResponse> errorResponse(
		ErrorCode errorCode,
		List<ErrorDetail> details,
		String traceId
	) {
		return errorResponse(errorCode, errorCode.message(), details, traceId);
	}

	private ResponseEntity<ErrorResponse> errorResponse(
		ErrorCode errorCode,
		String message,
		List<ErrorDetail> details,
		String traceId
	) {
		ErrorResponse response = ErrorResponse.failure(
			errorCode.code(),
			message,
			details,
			traceId
		);
		return ResponseEntity.status(errorCode.status()).body(response);
	}

	private boolean hasClientAbortCause(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof ClientAbortException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private boolean shouldExpireRefreshCookie(
		ErrorCode errorCode,
		HttpServletRequest request
	) {
		String requestUri = request.getRequestURI();
		boolean sessionEndpoint = "/api/auth/refresh".equals(requestUri)
			|| "/api/auth/session/activity".equals(requestUri);
		if (!sessionEndpoint) {
			return false;
		}
		return errorCode == ErrorCode.TOKEN_INVALID
			|| errorCode == ErrorCode.USER_INACTIVE
			|| errorCode == ErrorCode.ACCOUNT_SUSPENDED
			|| errorCode == ErrorCode.AUTH_SESSION_IDLE_EXPIRED
			|| errorCode == ErrorCode.AUTH_SESSION_ABSOLUTE_EXPIRED;
	}

	private void logClientDisconnect(HttpServletRequest request) {
		Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
		if (traceId == null) {
			log.debug("Client disconnected during async response");
			return;
		}
		log.atDebug()
			.addKeyValue("traceId", traceId)
			.log("Client disconnected during async response");
	}

	private String traceId(HttpServletRequest request) {
		Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
		return traceId == null ? "unknown" : traceId.toString();
	}
}
