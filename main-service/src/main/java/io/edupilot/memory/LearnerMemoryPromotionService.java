package io.edupilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class LearnerMemoryPromotionService {

	private static final Logger log =
		LoggerFactory.getLogger(LearnerMemoryPromotionService.class);

	private final LearnerMemoryPromotionTransaction transaction;

	public LearnerMemoryPromotionService(
		LearnerMemoryPromotionTransaction transaction
	) {
		this.transaction = transaction;
	}

	public boolean promoteMemory(
		Long userId,
		Long materialId,
		MemoryWrite write
	) {
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				boolean promoted = transaction.promote(
					userId,
					materialId,
					write
				);
				if (!promoted) {
					log.atWarn()
						.addKeyValue("userId", userId)
						.addKeyValue("materialId", materialId)
						.addKeyValue(
							"candidateCount",
							write == null || write.candidateIds() == null
								? 0
								: write.candidateIds().size()
						)
						.log("Learner memory promotion rejected");
				}
				return promoted;
			} catch (
				OptimisticLockingFailureException
					| DataIntegrityViolationException exception
			) {
				if (attempt == 1) {
					throw exception;
				}
			}
		}
		return false;
	}
}
