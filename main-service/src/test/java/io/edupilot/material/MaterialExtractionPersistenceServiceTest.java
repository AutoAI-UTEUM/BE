package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.dto.ExtractedPage;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class MaterialExtractionPersistenceServiceTest {

	@Mock
	private LearningMaterialRepository materialRepository;

	@Mock
	private MaterialPageRepository pageRepository;

	@Test
	void deletedMaterialCannotReturnToReady() {
		User owner = User.create("owner@example.com", "hash", "소유자");
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/key.pdf"
		);
		material.delete();
		when(materialRepository.findByIdForUpdate(10L))
			.thenReturn(Optional.of(material));
		MaterialExtractionPersistenceService service =
			new MaterialExtractionPersistenceService(
				materialRepository,
				pageRepository
			);

		boolean applied = service.complete(
			10L,
			List.of(new ExtractedPage(1, "text"))
		);

		assertThat(applied).isFalse();
		assertThat(material.getStatus()).isEqualTo(MaterialStatus.DELETED);
		assertThat(material.getProcessingStatus())
			.isEqualTo(MaterialProcessingStatus.PROCESSING);
		verify(pageRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
	}
}
