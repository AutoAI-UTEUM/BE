package io.edupilot.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialStatus;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class LearnerMemoryServiceTest {

	@Mock
	private LearningMaterialRepository materialRepository;

	@Mock
	private LearnerMemoryRepository memoryRepository;

	@Test
	void returnsEmptySummaryInsteadOfNotFoundWhenMemoryDoesNotExist() {
		LearningMaterial material = material();
		when(materialRepository.findByIdAndOwner_IdAndStatus(
			10L,
			1L,
			MaterialStatus.ACTIVE
		)).thenReturn(Optional.of(material));
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());

		var response = service().get(1L, 10L);

		assertThat(response.materialId()).isEqualTo(10L);
		assertThat(response.strengths()).isEmpty();
		assertThat(response.memoryDigest()).isNull();
		assertThat(response.updatedAt()).isNull();
	}

	@Test
	void returnsOnlyPublicMemorySummary() {
		LearningMaterial material = material();
		User user = user();
		LearnerMemory memory = LearnerMemory.create(user, material);
		ReflectionTestUtils.setField(memory, "strengths", List.of("강점"));
		ReflectionTestUtils.setField(memory, "weaknesses", List.of("약점"));
		ReflectionTestUtils.setField(
			memory,
			"misconceptions",
			List.of("내부 오개념")
		);
		ReflectionTestUtils.setField(
			memory,
			"explanationPreferences",
			List.of("예시 선호")
		);
		ReflectionTestUtils.setField(
			memory,
			"preferredQuizTypes",
			List.of("MCQ")
		);
		ReflectionTestUtils.setField(memory, "targetDifficulty", "BALANCED");
		ReflectionTestUtils.setField(
			memory,
			"nextCoachingGoals",
			List.of("내부 코칭 목표")
		);
		ReflectionTestUtils.setField(memory, "memoryDigest", "공개 digest");
		ReflectionTestUtils.setField(
			memory,
			"updatedAt",
			Instant.parse("2026-07-26T10:00:00Z")
		);
		when(materialRepository.findByIdAndOwner_IdAndStatus(
			10L,
			1L,
			MaterialStatus.ACTIVE
		)).thenReturn(Optional.of(material));
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.of(memory));

		var response = service().get(1L, 10L);

		assertThat(response.strengths()).containsExactly("강점");
		assertThat(response.weaknesses()).containsExactly("약점");
		assertThat(response.explanationPreferences())
			.containsExactly("예시 선호");
		assertThat(response.preferredQuizTypes()).containsExactly("MCQ");
		assertThat(response.memoryDigest()).isEqualTo("공개 digest");
	}

	@Test
	void hidesOtherUsersMaterialAsNotFound() {
		when(materialRepository.findByIdAndOwner_IdAndStatus(
			10L,
			1L,
			MaterialStatus.ACTIVE
		)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().get(1L, 10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
	}

	private LearnerMemoryService service() {
		return new LearnerMemoryService(
			materialRepository,
			memoryRepository
		);
	}

	private User user() {
		User user = User.create("user@example.com", "hash", "사용자");
		ReflectionTestUtils.setField(user, "id", 1L);
		return user;
	}

	private LearningMaterial material() {
		LearningMaterial material = LearningMaterial.create(
			user(),
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		return material;
	}
}
