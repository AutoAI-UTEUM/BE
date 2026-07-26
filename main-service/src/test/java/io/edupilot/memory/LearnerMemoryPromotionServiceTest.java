package io.edupilot.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class LearnerMemoryPromotionServiceTest {

	@Mock
	private LearnerMemoryRepository memoryRepository;

	@Mock
	private LearnerMemoryCandidateRepository candidateRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private LearningMaterialRepository materialRepository;

	@Mock
	private LearnerMemoryPromotionTransaction transaction;

	@Test
	void promotesWithTwoIndependentHighConfidenceCandidatesAndKeepsRecords() {
		User user = user();
		LearningMaterial material = material(user);
		MemoryWrite write = write();
		LearnerMemoryCandidate first = candidate(
			user,
			material,
			1L,
			new BigDecimal("0.80"),
			new MemoryEvidenceRef("QUIZ_ASSESSMENT", 101L, 1001L)
		);
		LearnerMemoryCandidate second = candidate(
			user,
			material,
			2L,
			new BigDecimal("0.75"),
			new MemoryEvidenceRef("QUIZ_ASSESSMENT", 102L, 1002L)
		);
		when(candidateRepository
			.findByIdInAndUser_IdAndMaterial_IdAndStatus(
				any(),
				org.mockito.ArgumentMatchers.eq(1L),
				org.mockito.ArgumentMatchers.eq(10L),
				org.mockito.ArgumentMatchers.eq(
					MemoryCandidateStatus.CANDIDATE
				)
			)).thenReturn(List.of(first, second));
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());
		when(userRepository.getReferenceById(1L)).thenReturn(user);
		when(materialRepository.getReferenceById(10L)).thenReturn(material);

		boolean promoted = promotionTransaction().promote(1L, 10L, write);

		assertThat(promoted).isTrue();
		assertThat(first.getStatus())
			.isEqualTo(MemoryCandidateStatus.PROMOTED);
		assertThat(second.getStatus())
			.isEqualTo(MemoryCandidateStatus.PROMOTED);
		verify(candidateRepository, never()).delete(any());
		verify(memoryRepository).saveAndFlush(any(LearnerMemory.class));
	}

	@Test
	void rejectsLowConfidenceOrNonIndependentEvidenceWithoutWriting() {
		User user = user();
		LearningMaterial material = material(user);
		MemoryEvidenceRef same =
			new MemoryEvidenceRef("QUIZ_ASSESSMENT", 101L, 1001L);
		LearnerMemoryCandidate first = candidate(
			user,
			material,
			1L,
			new BigDecimal("0.80"),
			same
		);
		LearnerMemoryCandidate second = candidate(
			user,
			material,
			2L,
			new BigDecimal("0.69"),
			same
		);
		when(candidateRepository
			.findByIdInAndUser_IdAndMaterial_IdAndStatus(
				any(),
				org.mockito.ArgumentMatchers.eq(1L),
				org.mockito.ArgumentMatchers.eq(10L),
				org.mockito.ArgumentMatchers.eq(
					MemoryCandidateStatus.CANDIDATE
				)
			)).thenReturn(List.of(first, second));

		assertThat(promotionTransaction().promote(1L, 10L, write()))
			.isFalse();
		verify(memoryRepository, never()).saveAndFlush(any());
		assertThat(first.getStatus())
			.isEqualTo(MemoryCandidateStatus.CANDIDATE);
	}

	@Test
	void optimisticConflictRetriesExactlyOnceInFreshBoundary() {
		MemoryWrite write = write();
		when(transaction.promote(1L, 10L, write))
			.thenThrow(new OptimisticLockingFailureException("conflict"))
			.thenReturn(true);

		boolean promoted = new LearnerMemoryPromotionService(transaction)
			.promoteMemory(1L, 10L, write);

		assertThat(promoted).isTrue();
		verify(transaction, org.mockito.Mockito.times(2))
			.promote(1L, 10L, write);
	}

	private LearnerMemoryPromotionTransaction promotionTransaction() {
		return new LearnerMemoryPromotionTransaction(
			memoryRepository,
			candidateRepository,
			userRepository,
			materialRepository
		);
	}

	private MemoryWrite write() {
		return new MemoryWrite(
			List.of("강점"),
			List.of("약점"),
			List.of("오개념"),
			List.of("예시 선호"),
			List.of("MCQ"),
			"BALANCED",
			List.of("목표"),
			"digest",
			List.of(1L, 2L)
		);
	}

	private LearnerMemoryCandidate candidate(
		User user,
		LearningMaterial material,
		Long id,
		BigDecimal confidence,
		MemoryEvidenceRef evidence
	) {
		LearnerMemoryCandidate candidate =
			LearnerMemoryCandidate.create(
				user,
				material,
				"WEAKNESS",
				"내용",
				confidence,
				List.of(evidence),
				"1.0"
			);
		ReflectionTestUtils.setField(candidate, "id", id);
		return candidate;
	}

	private User user() {
		User user = User.create("user@example.com", "hash", "사용자");
		ReflectionTestUtils.setField(user, "id", 1L);
		return user;
	}

	private LearningMaterial material(User user) {
		LearningMaterial material = LearningMaterial.create(
			user,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		return material;
	}
}
