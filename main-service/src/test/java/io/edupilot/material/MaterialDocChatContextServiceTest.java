package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class MaterialDocChatContextServiceTest {

	@Mock
	private MaterialAccessService accessService;

	@Mock
	private MaterialPageRepository pageRepository;

	@Test
	void buildsAtMostTenOrderedDocumentsWithMergedCaptions() {
		LearningMaterial material = material();
		material.markReady(11);
		List<MaterialPage> pages = java.util.stream.IntStream.rangeClosed(1, 11)
			.mapToObj(page -> MaterialPage.create(material, page, "text-" + page))
			.toList();
		pages.getFirst().updateCaption("diagram");
		when(accessService.requireAccessible(1L, 10L)).thenReturn(material);
		when(pageRepository.findByMaterial_IdOrderByPageNumberAsc(10L))
			.thenReturn(pages);
		MaterialDocChatContextService service = new MaterialDocChatContextService(
			accessService,
			pageRepository,
			new DocChatPageContextBuilder(new MaterialPageTextMerger())
		);

		var documents = service.build(1L, 10L);

		assertThat(documents).hasSize(6);
		assertThat(documents.getFirst().title()).isEqualTo("material p.1-2");
		assertThat(documents.getFirst().text())
			.contains("[p.1]", "text-1", "diagram", "[p.2]", "text-2");
		assertThat(documents.getLast().title()).isEqualTo("material p.11-11");
	}

	@Test
	void mapsNonReadyStatesToExistingConflicts() {
		LearningMaterial processing = material();
		when(accessService.requireAccessible(1L, 10L)).thenReturn(processing);
		MaterialDocChatContextService service = service();

		assertThatThrownBy(() -> service.build(1L, 10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_PROCESSING)
			);

		LearningMaterial failed = material();
		failed.markFailed(MaterialFailureReason.EXTRACTION_FAILED, null);
		when(accessService.requireAccessible(1L, 11L)).thenReturn(failed);
		assertThatThrownBy(() -> service.build(1L, 11L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_PROCESSING_FAILED)
			);
	}

	@Test
	void preservesHiddenMaterialAccessFailure() {
		when(accessService.requireAccessible(1L, 99L)).thenThrow(
			new BusinessException(ErrorCode.MATERIAL_NOT_FOUND)
		);

		assertThatThrownBy(() -> service().build(1L, 99L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
	}

	private MaterialDocChatContextService service() {
		return new MaterialDocChatContextService(
			accessService,
			pageRepository,
			new DocChatPageContextBuilder(new MaterialPageTextMerger())
		);
	}

	private LearningMaterial material() {
		User owner = User.create("owner@example.com", "hash", "owner");
		ReflectionTestUtils.setField(owner, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			owner,
			"material",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		return material;
	}
}
