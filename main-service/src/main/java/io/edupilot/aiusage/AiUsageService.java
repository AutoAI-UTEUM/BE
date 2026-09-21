package io.edupilot.aiusage;

import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.edupilot.ai.dto.AiUsage;

@Service
public class AiUsageService {

	private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

	private final AiUsageLogRepository repository;

	public AiUsageService(AiUsageLogRepository repository) {
		this.repository = repository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(
		Long userId,
		AiFeature feature,
		AiUsage usage,
		boolean success
	) {
		record(userId, feature, usage, success, null);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(
		Long userId,
		AiFeature feature,
		AiUsage usage,
		boolean success,
		String requestId
	) {
		try {
			repository.saveAndFlush(AiUsageLog.create(
				userId,
				feature,
				usage,
				success,
				normalizeRequestId(requestId)
			));
		} catch (DataIntegrityViolationException exception) {
			rollbackIfActive();
			if (isRequestIdDuplicate(exception)) {
				// request_id는 내부 중복 기록 방지용이다. 정확 과금 원장은 xAI Management다.
				log.atDebug()
					.addKeyValue("userId", userId)
					.addKeyValue("feature", feature)
					.log("Skipped duplicate AI usage record");
			} else {
				log.atWarn()
					.addKeyValue("userId", userId)
					.addKeyValue("feature", feature)
					.log("Failed to record AI usage due to data integrity");
			}
		} catch (RuntimeException exception) {
			rollbackIfActive();
			log.atWarn()
				.addKeyValue("userId", userId)
				.addKeyValue("feature", feature)
				.log("Failed to record AI usage");
		}
	}

	private String normalizeRequestId(String requestId) {
		if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
			return null;
		}
		return requestId;
	}

	private void rollbackIfActive() {
		// 저장 실패로 표시된 트랜잭션을 조용히 롤백해 호출부 전파를 막는다.
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
		}
	}

	private boolean isRequestIdDuplicate(
		DataIntegrityViolationException exception
	) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
				&& containsRequestId(violation.getConstraintName())) {
				return true;
			}
			if (containsRequestId(cause.getMessage())) {
				return true;
			}
		}
		return false;
	}

	private boolean containsRequestId(String value) {
		return value != null
			&& value.toLowerCase(Locale.ROOT).contains("request_id");
	}
}
