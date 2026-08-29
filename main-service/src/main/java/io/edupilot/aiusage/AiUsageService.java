package io.edupilot.aiusage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
		try {
			repository.saveAndFlush(AiUsageLog.create(
				userId,
				feature,
				usage,
				success
			));
		} catch (RuntimeException exception) {
			// 저장 실패로 표시된 트랜잭션을 조용히 롤백해 호출부 전파를 막는다.
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			}
			log.atWarn()
				.addKeyValue("userId", userId)
				.addKeyValue("feature", feature)
				.log("Failed to record AI usage");
		}
	}
}
