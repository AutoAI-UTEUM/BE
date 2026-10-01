package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class MaterialAccessServiceTest {

	@Mock
	private LearningMaterialRepository materialRepository;
	@Mock
	private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Mock
	private LearningSessionRepository sessionRepository;

	@Test
	void allowsOwnerOrMemberOfLinkedClassroomAndHidesOthers() {
		LearningMaterial material = material(1L, 10L);
		when(materialRepository.findById(10L)).thenReturn(Optional.of(material));
		MaterialAccessService service = service();

		assertThat(service.requireAccessible(1L, 10L)).isSameAs(material);

		when(weekMaterialRepository.existsAccess(2L, 10L))
			.thenReturn(true);
		assertThat(service.requireAccessible(2L, 10L)).isSameAs(material);

		assertThatThrownBy(() -> service.requireAccessible(3L, 10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
		verify(weekMaterialRepository).existsAccess(3L, 10L);
	}

	private MaterialAccessService service() {
		return new MaterialAccessService(
			materialRepository,
			weekMaterialRepository,
			sessionRepository
		);
	}

	@Test
	void writeAccessLocksCurrentGrantsAndStillAllowsOwner() {
		LearningMaterial material = material(1L, 10L);
		when(materialRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(material));
		assertThat(service().requireAccessibleForUpdate(1L, 10L)).isSameAs(material);
		org.mockito.Mockito.verifyNoInteractions(weekMaterialRepository);
		when(weekMaterialRepository.lockAccessGrants(2L, 10L)).thenReturn(java.util.List.of(5L));
		assertThat(service().requireAccessibleForUpdate(2L, 10L)).isSameAs(material);
		assertThatThrownBy(() -> service().requireAccessibleForUpdate(3L, 10L))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_FOUND));
	}

	private LearningMaterial material(Long ownerId, Long materialId) {
		User owner = User.create("owner@example.com", "hash", "Owner");
		ReflectionTestUtils.setField(owner, "id", ownerId);
		LearningMaterial material = LearningMaterial.create(
			owner, "Material", "materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", materialId);
		return material;
	}
}
