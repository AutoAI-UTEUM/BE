package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {

	@Mock
	private LearningMaterialRepository materialRepository;

	@Mock
	private MaterialPageRepository pageRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private FileStorage fileStorage;

	@Mock
	private MaterialDeletionGuard deletionGuard;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private MaterialService materialService;
	private User owner;

	@BeforeEach
	void setUp() {
		materialService = new MaterialService(
			materialRepository,
			pageRepository,
			userRepository,
			fileStorage,
			new MaterialProperties(45, 300),
			deletionGuard,
			eventPublisher
		);
		owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
	}

	@Test
	void uploadStoresPdfAndPublishesProcessingMaterial() {
		MockMultipartFile file = pdf("%PDF-valid");
		when(fileStorage.store(any(InputStream.class)))
			.thenReturn("materials/00000000-0000-0000-0000-000000000001.pdf");
		when(userRepository.getReferenceById(1L)).thenReturn(owner);
		when(materialRepository.save(any(LearningMaterial.class))).thenAnswer(invocation -> {
			LearningMaterial material = invocation.getArgument(0);
			ReflectionTestUtils.setField(material, "id", 10L);
			ReflectionTestUtils.setField(
				material,
				"createdAt",
				Instant.parse("2026-07-25T00:00:00Z")
			);
			return material;
		});

		var response = materialService.upload(1L, file, "  선형회귀  ");

		assertThat(response.materialId()).isEqualTo(10L);
		assertThat(response.title()).isEqualTo("선형회귀");
		assertThat(response.processingStatus())
			.isEqualTo(MaterialProcessingStatus.PROCESSING);
		assertThat(response.pageCount()).isNull();
		ArgumentCaptor<MaterialExtractionRequested> event =
			ArgumentCaptor.forClass(MaterialExtractionRequested.class);
		verify(eventPublisher).publishEvent(event.capture());
		assertThat(event.getValue().materialId()).isEqualTo(10L);
	}

	@Test
	void uploadChecksSizeBeforeFileTypeAndTitle() {
		MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
		when(file.getSize()).thenReturn(45L * 1024L * 1024L + 1L);

		assertThatThrownBy(() -> materialService.upload(1L, file, ""))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE)
			);
		verify(fileStorage, never()).store(any());
	}

	@Test
	void uploadRejectsWrongMagicBytes() {
		MockMultipartFile file = new MockMultipartFile(
			"file",
			"fake.pdf",
			"application/pdf",
			"not-pdf".getBytes()
		);

		assertThatThrownBy(() -> materialService.upload(1L, file, "자료"))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_PDF_FILE)
			);
	}

	@Test
	void deleteChecksOwnershipThenGuardThenMarksDeleted() {
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/00000000-0000-0000-0000-000000000001.pdf"
		);
		when(materialRepository.findByIdForUpdate(10L))
			.thenReturn(Optional.of(material));

		materialService.delete(1L, 10L);

		assertThat(material.getStatus()).isEqualTo(MaterialStatus.DELETED);
		InOrder order = inOrder(materialRepository, deletionGuard);
		order.verify(materialRepository).findByIdForUpdate(10L);
		order.verify(deletionGuard).assertDeletable(10L);
	}

	@Test
	void listFiltersByOwnerAndActiveStatusWithStableLatestSort() {
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/00000000-0000-0000-0000-000000000001.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		ReflectionTestUtils.setField(
			material,
			"createdAt",
			Instant.parse("2026-07-25T00:00:00Z")
		);
		when(materialRepository.findByOwner_IdAndStatus(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(MaterialStatus.ACTIVE),
			any(Pageable.class)
		)).thenAnswer(invocation -> {
			Pageable pageable = invocation.getArgument(2);
			return new PageImpl<>(List.of(material), pageable, 1);
		});

		var response = materialService.list(1L, 0, 20);

		assertThat(response.items()).hasSize(1);
		assertThat(response.items().getFirst().materialId()).isEqualTo(10L);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(materialRepository).findByOwner_IdAndStatus(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(MaterialStatus.ACTIVE),
			pageable.capture()
		);
		assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending())
			.isTrue();
		assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending())
			.isTrue();
	}

	@Test
	void pageTextRejectsProcessingAndOutOfRangeStates() {
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/00000000-0000-0000-0000-000000000001.pdf"
		);
		when(materialRepository.findByIdAndOwner_IdAndStatus(
			10L,
			1L,
			MaterialStatus.ACTIVE
		)).thenReturn(Optional.of(material));

		assertThatThrownBy(() -> materialService.page(1L, 10L, 1))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATERIAL_PROCESSING)
			);

		material.markReady(2);
		assertThatThrownBy(() -> materialService.page(1L, 10L, 3))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAGE_OUT_OF_RANGE)
			);
	}

	@Test
	void deleteHidesOtherOwnersMaterial() {
		User other = User.create("other@example.com", "hash", "타인");
		ReflectionTestUtils.setField(other, "id", 2L);
		LearningMaterial material = LearningMaterial.create(
			other,
			"자료",
			"materials/00000000-0000-0000-0000-000000000001.pdf"
		);
		when(materialRepository.findByIdForUpdate(10L))
			.thenReturn(Optional.of(material));

		assertThatThrownBy(() -> materialService.delete(1L, 10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
		verify(deletionGuard, never()).assertDeletable(any());
	}

	private MockMultipartFile pdf(String content) {
		return new MockMultipartFile(
			"file",
			"original.pdf",
			"application/pdf",
			content.getBytes()
		);
	}
}
