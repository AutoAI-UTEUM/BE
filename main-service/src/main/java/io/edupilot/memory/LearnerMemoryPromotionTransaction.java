package io.edupilot.memory;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
class LearnerMemoryPromotionTransaction {

	private static final BigDecimal MIN_CONFIDENCE =
		new BigDecimal("0.70");

	private final LearnerMemoryRepository memoryRepository;
	private final LearnerMemoryCandidateRepository candidateRepository;
	private final UserRepository userRepository;
	private final LearningMaterialRepository materialRepository;

	LearnerMemoryPromotionTransaction(
		LearnerMemoryRepository memoryRepository,
		LearnerMemoryCandidateRepository candidateRepository,
		UserRepository userRepository,
		LearningMaterialRepository materialRepository
	) {
		this.memoryRepository = memoryRepository;
		this.candidateRepository = candidateRepository;
		this.userRepository = userRepository;
		this.materialRepository = materialRepository;
	}

	@Transactional
	public boolean promote(
		Long userId,
		Long materialId,
		MemoryWrite write
	) {
		if (!validWrite(write)) {
			return false;
		}
		Set<Long> requestedIds = new HashSet<>(write.candidateIds());
		List<LearnerMemoryCandidate> candidates = candidateRepository
			.findByIdInAndUser_IdAndMaterial_IdAndStatus(
				requestedIds,
				userId,
				materialId,
				MemoryCandidateStatus.CANDIDATE
			);
		if (candidates.size() != requestedIds.size()
			|| candidates.stream().anyMatch(candidate ->
				candidate.getConfidence().compareTo(MIN_CONFIDENCE) < 0)
			|| independentEvidenceCount(candidates) < 2) {
			return false;
		}

		LearnerMemory memory = memoryRepository
			.findByUser_IdAndMaterial_Id(userId, materialId)
			.orElseGet(() -> {
				User user = userRepository.getReferenceById(userId);
				LearningMaterial material =
					materialRepository.getReferenceById(materialId);
				return LearnerMemory.create(user, material, write);
			});
		if (memory.getId() != null) {
			memory.apply(write);
		}
		memoryRepository.saveAndFlush(memory);
		candidates.forEach(LearnerMemoryCandidate::promote);
		candidateRepository.flush();
		return true;
	}

	private boolean validWrite(MemoryWrite write) {
		return write != null
			&& write.strengths() != null
			&& write.weaknesses() != null
			&& write.misconceptions() != null
			&& write.explanationPreferences() != null
			&& write.preferredQuizTypes() != null
			&& write.nextCoachingGoals() != null
			&& write.candidateIds() != null
			&& new HashSet<>(write.candidateIds()).size() >= 2;
	}

	private long independentEvidenceCount(
		List<LearnerMemoryCandidate> candidates
	) {
		return candidates.stream()
			.flatMap(candidate -> candidate.getEvidenceRefs().stream())
			.map(reference ->
				reference.sourceType()
					+ ":"
					+ reference.sourceId()
					+ ":"
					+ reference.sessionId())
			.distinct()
			.count();
	}
}
