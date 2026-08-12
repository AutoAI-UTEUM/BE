package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.Duration;
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
import io.edupilot.classroom.ClassroomWeekService;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

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
	@Mock
	private MaterialAccessService accessService;
	@Mock
	private ClassroomWeekService weekService;

	private MaterialService materialService;
	private User owner;

	@BeforeEach
	void setUp() {
		materialService = new MaterialService(
			materialRepository,
			pageRepository,
			userRepository,
			fileStorage,
			new MaterialProperties(45, 300, Duration.ofMinutes(30)),
			deletionGuard,
			eventPublisher,
			accessService,
			weekService
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
		when(materialRepository.saveAndFlush(any(LearningMaterial.class))).thenAnswer(invocation -> {
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
		assertThat(response.failureReason()).isNull();
		assertThat(response.traceId()).isNull();
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
	void classroomUploadLinksMaterialAndRequiresBothTargetParts() {
		MockMultipartFile file = pdf("%PDF-valid");
		when(fileStorage.store(any(InputStream.class))).thenReturn("materials/class.pdf");
		when(userRepository.getReferenceById(1L)).thenReturn(owner);
		when(materialRepository.saveAndFlush(any(LearningMaterial.class)))
			.thenAnswer(invocation -> {
				LearningMaterial saved = invocation.getArgument(0);
				ReflectionTestUtils.setField(saved, "id", 10L);
				return saved;
			});

		materialService.upload(
			1L, UserRole.INSTRUCTOR, file, "Class material", 30L, 1
		);

		verify(weekService).linkUploadedMaterial(
			eq(1L),
			eq(UserRole.INSTRUCTOR),
			eq(30L),
			eq(1),
			any(LearningMaterial.class)
		);
		assertThatThrownBy(() -> materialService.upload(
			1L, UserRole.INSTRUCTOR, file, "Class material", 30L, null
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
		);
	}

	@Test
	void classroomLinkFailureCompensatesStoredFileOutsideTransactionProxy() {
		MockMultipartFile file = pdf("%PDF-valid");
		when(fileStorage.store(any(InputStream.class))).thenReturn("materials/class.pdf");
		when(userRepository.getReferenceById(1L)).thenReturn(owner);
		when(materialRepository.saveAndFlush(any(LearningMaterial.class)))
			.thenAnswer(invocation -> {
				LearningMaterial saved = invocation.getArgument(0);
				ReflectionTestUtils.setField(saved, "id", 10L);
				return saved;
			});
		org.mockito.Mockito.doThrow(
			new BusinessException(ErrorCode.WEEK_NOT_FOUND)
		).when(weekService).linkUploadedMaterial(
			eq(1L),
			eq(UserRole.INSTRUCTOR),
			eq(30L),
			eq(1),
			any(LearningMaterial.class)
		);

		assertThatThrownBy(() -> materialService.upload(
			1L, UserRole.INSTRUCTOR, file, "Class material", 30L, 1
		)).isInstanceOf(BusinessException.class);
		verify(fileStorage).delete("materials/class.pdf");
	}

	@Test
	void renameUpdatesOnlyOwnedActiveMaterialTitle() {
		LearningMaterial material = LearningMaterial.create(
			owner,
			"기존 제목",
			"materials/00000000-0000-0000-0000-000000000001.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		when(materialRepository.findByIdAndOwner_IdAndStatus(
			10L,
			1L,
			MaterialStatus.ACTIVE
		)).thenReturn(Optional.of(material));

		var response = materialService.rename(1L, 10L, "  새 제목  ");

		assertThat(material.getTitle()).isEqualTo("새 제목");
		assertThat(response.title()).isEqualTo("새 제목");
		assertThat(material.getStorageKey()).isEqualTo(
			"materials/00000000-0000-0000-0000-000000000001.pdf"
		);
		assertThat(material.getProcessingStatus())
			.isEqualTo(MaterialProcessingStatus.PROCESSING);
		verify(materialRepository).flush();
		verifyNoInteractions(accessService);
	}

	@Test
	void renameHidesOtherOwnersAndDeletedMaterials() {
		when(materialRepository.findByIdAndOwner_IdAndStatus(
			10L,
			2L,
			MaterialStatus.ACTIVE
		)).thenReturn(Optional.empty());
		when(materialRepository.findByIdAndOwner_IdAndStatus(
			11L,
			1L,
			MaterialStatus.ACTIVE
		)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> materialService.rename(2L, 10L, "새 제목"))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
		assertThatThrownBy(() -> materialService.rename(1L, 11L, "새 제목"))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
		verifyNoInteractions(accessService);
	}

	@Test
	void renameRejectsBlankAndOversizedTitles() {
		for (String title : List.of("", "   ", "a".repeat(256))) {
			assertThatThrownBy(() -> materialService.rename(1L, 10L, title))
				.isInstanceOfSatisfying(BusinessException.class, exception ->
					assertThat(exception.errorCode())
						.isEqualTo(ErrorCode.VALIDATION_FAILED)
				);
		}
		verify(materialRepository, never())
			.findByIdAndOwner_IdAndStatus(any(), any(), any());
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
		assertThat(response.items().getFirst().failureReason()).isNull();
		assertThat(response.items().getFirst().traceId()).isNull();
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
	void detailExposesStoredFailureAndAllowsLegacyFailureWithoutMetadata() {
		LearningMaterial failed = LearningMaterial.create(
			owner,
			"failed",
			"materials/failed.pdf"
		);
		failed.markFailed(
			MaterialFailureReason.EXTRACTION_FAILED,
			"upload-trace-10"
		);
		LearningMaterial legacyFailed = LearningMaterial.create(
			owner,
			"legacy failed",
			"materials/legacy-failed.pdf"
		);
		ReflectionTestUtils.setField(
			legacyFailed,
			"processingStatus",
			MaterialProcessingStatus.FAILED
		);
		when(accessService.requireAccessible(1L, 10L)).thenReturn(failed);
		when(accessService.requireAccessible(1L, 11L)).thenReturn(legacyFailed);

		var failedResponse = materialService.detail(1L, 10L);
		var legacyResponse = materialService.detail(1L, 11L);

		assertThat(failedResponse.failureReason())
			.isEqualTo(MaterialFailureReason.EXTRACTION_FAILED);
		assertThat(failedResponse.traceId()).isEqualTo("upload-trace-10");
		assertThat(legacyResponse.processingStatus())
			.isEqualTo(MaterialProcessingStatus.FAILED);
		assertThat(legacyResponse.failureReason()).isNull();
		assertThat(legacyResponse.traceId()).isNull();
	}

	@Test
	void pageTextRejectsProcessingAndOutOfRangeStates() {
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/00000000-0000-0000-0000-000000000001.pdf"
		);
		when(accessService.requireAccessible(1L, 10L)).thenReturn(material);

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
