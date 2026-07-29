package io.edupilot.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
	void promotesSingleCandidateWithTwoIndependentEvidenceAtThreshold() {
		User user = user();
		LearningMaterial material = material(user);
		MemoryWrite write = write(List.of(1L));
		LearnerMemoryCandidate first = candidate(
			user,
			material,
			1L,
			new BigDecimal("0.70"),
			new MemoryEvidenceRef(
				"TURN",
				501L,
				1001L,
				"assessment-1"
			),
			new MemoryEvidenceRef(
				"TURN",
				501L,
				1001L,
				"qa-2"
			)
		);
		when(candidateRepository
			.findByIdInAndUser_IdAndMaterial_IdAndStatus(
				any(),
				org.mockito.ArgumentMatchers.eq(1L),
				org.mockito.ArgumentMatchers.eq(10L),
				org.mockito.ArgumentMatchers.eq(
					MemoryCandidateStatus.CANDIDATE
				)
			)).thenReturn(List.of(first));
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());
		when(userRepository.getReferenceById(1L)).thenReturn(user);
		when(materialRepository.getReferenceById(10L)).thenReturn(material);

		boolean promoted = promotionTransaction().promote(1L, 10L, write);

		assertThat(promoted).isTrue();
		assertThat(first.getStatus())
			.isEqualTo(MemoryCandidateStatus.PROMOTED);
		verify(candidateRepository, never()).delete(any());
		ArgumentCaptor<LearnerMemory> memory =
			ArgumentCaptor.forClass(LearnerMemory.class);
		verify(memoryRepository).saveAndFlush(memory.capture());
		assertThat(memory.getValue().getMemoryDigest())
			.isEqualTo("digest");
	}

	@Test
	void rejectsSingleIndependentEvidenceWithoutWriting() {
		User user = user();
		LearningMaterial material = material(user);
		LearnerMemoryCandidate first = candidate(
			user,
			material,
			1L,
			new BigDecimal("0.80"),
			new MemoryEvidenceRef("QUIZ_ASSESSMENT", 101L, 1001L)
		);
		when(candidateRepository
			.findByIdInAndUser_IdAndMaterial_IdAndStatus(
				any(),
				org.mockito.ArgumentMatchers.eq(1L),
				org.mockito.ArgumentMatchers.eq(10L),
				org.mockito.ArgumentMatchers.eq(
					MemoryCandidateStatus.CANDIDATE
				)
			)).thenReturn(List.of(first));

		assertThat(promotionTransaction().promote(
			1L,
			10L,
			write(List.of(1L))
		))
			.isFalse();
		verify(memoryRepository, never()).saveAndFlush(any());
		assertThat(first.getStatus())
			.isEqualTo(MemoryCandidateStatus.CANDIDATE);
	}

	@Test
	void rejectsCandidateBelowConfidenceThreshold() {
		User user = user();
		LearningMaterial material = material(user);
		LearnerMemoryCandidate candidate = candidate(
			user,
			material,
			1L,
			new BigDecimal("0.69"),
			new MemoryEvidenceRef("QUIZ_ASSESSMENT", 101L, 1001L),
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
			)).thenReturn(List.of(candidate));

		assertThat(promotionTransaction().promote(
			1L,
			10L,
			write(List.of(1L))
		)).isFalse();
		verify(memoryRepository, never()).saveAndFlush(any());
		assertThat(candidate.getStatus())
			.isEqualTo(MemoryCandidateStatus.CANDIDATE);
	}

	@Test
	void rejectsDuplicateEvidenceAcrossSelectedCandidates() {
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
			new BigDecimal("0.80"),
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

		assertThat(promotionTransaction().promote(
			1L,
			10L,
			write(List.of(1L, 2L))
		)).isFalse();
		verify(memoryRepository, never()).saveAndFlush(any());
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

	@Test
	void policyRejectionIsWarnedAndReturnedWithoutException() {
		MemoryWrite write = write();
		when(transaction.promote(1L, 10L, write)).thenReturn(false);
		Logger logger = (Logger) LoggerFactory.getLogger(
			LearnerMemoryPromotionService.class
		);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThat(new LearnerMemoryPromotionService(transaction)
				.promoteMemory(1L, 10L, write))
				.isFalse();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list)
			.filteredOn(event -> event.getFormattedMessage().equals(
				"Learner memory promotion rejected"
			))
			.singleElement()
			.extracting(ILoggingEvent::getLevel)
			.isEqualTo(Level.WARN);
	}

	@Test
	void secondOptimisticConflictIsPropagatedAfterOneRetry() {
		MemoryWrite write = write();
		OptimisticLockingFailureException conflict =
			new OptimisticLockingFailureException("conflict");
		when(transaction.promote(1L, 10L, write))
			.thenThrow(conflict);

		assertThatThrownBy(() ->
			new LearnerMemoryPromotionService(transaction)
				.promoteMemory(1L, 10L, write)
		).isSameAs(conflict);
		verify(transaction, org.mockito.Mockito.times(2))
			.promote(1L, 10L, write);
	}

	@Test
	void promotionTransactionUsesRequiresNewBoundary() throws Exception {
		Transactional transactional = LearnerMemoryPromotionTransaction.class
			.getDeclaredMethod(
				"promote",
				Long.class,
				Long.class,
				MemoryWrite.class
			)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation())
			.isEqualTo(Propagation.REQUIRES_NEW);
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
		return write(List.of(1L));
	}

	private MemoryWrite write(List<Long> candidateIds) {
		return new MemoryWrite(
			List.of("강점"),
			List.of("약점"),
			List.of("오개념"),
			List.of("예시 선호"),
			List.of("MCQ"),
			"BALANCED",
			List.of("목표"),
			"digest",
			candidateIds
		);
	}

	private LearnerMemoryCandidate candidate(
		User user,
		LearningMaterial material,
		Long id,
		BigDecimal confidence,
		MemoryEvidenceRef... evidence
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
