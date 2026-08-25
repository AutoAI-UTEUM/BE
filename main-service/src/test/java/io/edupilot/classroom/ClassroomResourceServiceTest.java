package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.dto.CreateClassroomLinkResourceRequest;
import io.edupilot.classroom.dto.UpdateClassroomResourceRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialProperties;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ClassroomResourceServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-25T01:00:00Z");

	@Mock
	private ClassroomService classroomService;
	@Mock
	private ClassroomResourceRepository resourceRepository;
	@Mock
	private FileStorage fileStorage;

	private ClassroomResourceService service;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		service = new ClassroomResourceService(
			classroomService,
			resourceRepository,
			fileStorage,
			new MaterialProperties(45, 300, java.time.Duration.ofMinutes(30))
		);
		User instructor = User.create(
			"teacher@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		);
		ReflectionTestUtils.setField(instructor, "id", 1L);
		classroom = Classroom.create(
			instructor,
			"AI Basics",
			LocalDate.of(2026, 8, 1),
			LocalDate.of(2026, 8, 31),
			ClassroomColor.BLUE,
			null,
			"AAAA-BBBB"
		);
		ReflectionTestUtils.setField(classroom, "id", 30L);
	}

	@Test
	void createsAllowedFileWithTrimmedTitleAndGeneratedStoragePath() {
		ownerCanWrite();
		MockMultipartFile file = file("Lecture.PPTX", "slides");
		when(fileStorage.storeClassroomResource(any()))
			.thenReturn("classroom-resources/123e4567-e89b-12d3-a456-426614174000");
		when(resourceRepository.saveAndFlush(any())).thenAnswer(invocation ->
			persisted(invocation.getArgument(0), 70L)
		);

		var response = service.createFile(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			file,
			"  Week slides  ",
			2
		);

		assertThat(response.resourceId()).isEqualTo(70L);
		assertThat(response.type()).isEqualTo(ClassroomResourceType.FILE);
		assertThat(response.title()).isEqualTo("Week slides");
		assertThat(response.weekNumber()).isEqualTo(2);
		assertThat(response.fileName()).isEqualTo("Lecture.PPTX");
		assertThat(response.url()).isNull();
		verify(classroomService).assertWritable(classroom);
	}

	@Test
	void rejectsEmptyOversizedMissingExtensionAndDisallowedExtension() {
		ownerCanWrite();
		org.springframework.web.multipart.MultipartFile oversized =
			org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
		when(oversized.isEmpty()).thenReturn(false);
		when(oversized.getSize()).thenReturn(45L * 1024 * 1024 + 1);
		assertError(() -> service.createFile(
			1L, UserRole.INSTRUCTOR, 30L,
			file("empty.pdf", ""), "Empty", null
		), ErrorCode.VALIDATION_FAILED);
		assertError(() -> service.createFile(
			1L, UserRole.INSTRUCTOR, 30L,
			oversized,
			"Large", null
		), ErrorCode.FILE_TOO_LARGE);
		assertError(() -> service.createFile(
			1L, UserRole.INSTRUCTOR, 30L,
			file("README", "text"), "No extension", null
		), ErrorCode.VALIDATION_FAILED);
		assertError(() -> service.createFile(
			1L, UserRole.INSTRUCTOR, 30L,
			file("script.exe", "binary"), "Executable", null
		), ErrorCode.VALIDATION_FAILED);
		verify(fileStorage, never()).storeClassroomResource(any());
	}

	@Test
	void nonOwnerCannotStoreFile() {
		when(classroomService.requireOwnerForUpdate(
			2L, UserRole.LEARNER, 30L
		)).thenThrow(new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));

		assertError(() -> service.createFile(
			2L,
			UserRole.LEARNER,
			30L,
			file("notes.txt", "content"),
			"Notes",
			null
		), ErrorCode.CLASSROOM_NOT_FOUND);
		verify(fileStorage, never()).storeClassroomResource(any());
	}

	@Test
	void createsHttpLinkAndRejectsOtherProtocols() {
		ownerCanWrite();
		when(resourceRepository.saveAndFlush(any())).thenAnswer(invocation ->
			persisted(invocation.getArgument(0), 71L)
		);

		var response = service.createLink(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomLinkResourceRequest(
				" https://example.com/lecture ", " Reference ", null
			)
		);

		assertThat(response.type()).isEqualTo(ClassroomResourceType.LINK);
		assertThat(response.url()).isEqualTo("https://example.com/lecture");
		assertThat(response.fileName()).isNull();
		assertError(() -> service.createLink(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomLinkResourceRequest(
				"ftp://example.com/file", "FTP", null
			)
		), ErrorCode.VALIDATION_FAILED);
	}

	@Test
	void rejectsInvalidTitleAndWeekNumber() {
		ownerCanWrite();

		assertError(() -> service.createLink(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomLinkResourceRequest(
				"https://example.com", "   ", null
			)
		), ErrorCode.VALIDATION_FAILED);
		assertError(() -> service.createLink(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomLinkResourceRequest(
				"https://example.com", "x".repeat(201), null
			)
		), ErrorCode.VALIDATION_FAILED);
		assertError(() -> service.createLink(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new CreateClassroomLinkResourceRequest(
				"https://example.com", "Reference", 6
			)
		), ErrorCode.VALIDATION_FAILED);
	}

	@Test
	void listsVisibleResourcesByOptionalWeekFilter() {
		when(classroomService.requireVisible(
			2L, UserRole.LEARNER, 30L
		)).thenReturn(classroom);
		ClassroomResource resource = persisted(
			ClassroomResource.link(
				classroom, "Reference", 2, "https://example.com"
			),
			71L
		);
		when(resourceRepository.findByClassroom_IdAndWeekNumber(
			eq(30L), eq(2), any(Pageable.class)
		)).thenReturn(new PageImpl<>(List.of(resource)));

		var response = service.list(
			2L, UserRole.LEARNER, 30L, 2, 0, 20
		);

		assertThat(response.items()).singleElement()
			.satisfies(item -> assertThat(item.resourceId()).isEqualTo(71L));
	}

	@Test
	void fileDownloadUsesStoredMetadataAndLinkFileIsHidden() {
		ClassroomResource fileResource = persisted(
			ClassroomResource.file(
				classroom,
				"Image",
				null,
				"diagram.PNG",
				"image/png",
				4L,
				"classroom-resources/123e4567-e89b-12d3-a456-426614174000"
			),
			70L
		);
		when(resourceRepository.findWithClassroom(70L))
			.thenReturn(Optional.of(fileResource));
		when(classroomService.requireVisible(
			2L, UserRole.LEARNER, 30L
		)).thenReturn(classroom);
		when(fileStorage.load(fileResource.getStoragePath()))
			.thenReturn(new ByteArrayResource("file".getBytes()));

		ClassroomResourceFile result = service.file(
			2L, UserRole.LEARNER, 70L
		);

		assertThat(result.fileName()).isEqualTo("diagram.PNG");
		assertThat(result.inline()).isTrue();

		ClassroomResource link = persisted(
			ClassroomResource.link(
				classroom, "Link", null, "https://example.com"
			),
			71L
		);
		when(resourceRepository.findWithClassroom(71L))
			.thenReturn(Optional.of(link));
		assertError(() -> service.file(
			2L, UserRole.LEARNER, 71L
		), ErrorCode.RESOURCE_NOT_FOUND);
	}

	@Test
	void learnerCannotUpdateAndFileDeletionIsBestEffort() {
		ClassroomResource resource = persisted(
			ClassroomResource.file(
				classroom,
				"Notes",
				null,
				"notes.txt",
				"text/plain",
				4L,
				"classroom-resources/123e4567-e89b-12d3-a456-426614174000"
			),
			70L
		);
		when(resourceRepository.findWithClassroom(70L))
			.thenReturn(Optional.of(resource));
		when(classroomService.requireOwnerForUpdate(
			2L, UserRole.LEARNER, 30L
		)).thenThrow(new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		UpdateClassroomResourceRequest request = new UpdateClassroomResourceRequest();
		request.setTitle("Updated");

		assertError(() -> service.update(
			2L, UserRole.LEARNER, 70L, request
		), ErrorCode.CLASSROOM_NOT_FOUND);

		when(classroomService.requireOwnerForUpdate(
			1L, UserRole.INSTRUCTOR, 30L
		)).thenReturn(classroom);
		when(resourceRepository.findForUpdate(70L))
			.thenReturn(Optional.of(resource));
		org.mockito.Mockito.doThrow(new RuntimeException("storage unavailable"))
			.when(fileStorage).delete(resource.getStoragePath());

		service.delete(1L, UserRole.INSTRUCTOR, 70L);

		verify(resourceRepository).delete(resource);
		verify(fileStorage).delete(resource.getStoragePath());
	}

	private void ownerCanWrite() {
		when(classroomService.requireOwnerForUpdate(
			1L, UserRole.INSTRUCTOR, 30L
		)).thenReturn(classroom);
	}

	private MockMultipartFile file(String name, String content) {
		return new MockMultipartFile(
			"file",
			name,
			"application/octet-stream",
			content.getBytes()
		);
	}

	private ClassroomResource persisted(
		ClassroomResource resource,
		Long id
	) {
		ReflectionTestUtils.setField(resource, "id", id);
		ReflectionTestUtils.setField(resource, "createdAt", NOW);
		ReflectionTestUtils.setField(resource, "updatedAt", NOW);
		return resource;
	}

	private void assertError(
		org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
		ErrorCode code
	) {
		assertThatThrownBy(call).isInstanceOfSatisfying(
			BusinessException.class,
			exception -> assertThat(exception.errorCode()).isEqualTo(code)
		);
	}
}
