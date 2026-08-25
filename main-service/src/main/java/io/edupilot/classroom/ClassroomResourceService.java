package io.edupilot.classroom;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import io.edupilot.classroom.dto.ClassroomResourceListResponse;
import io.edupilot.classroom.dto.ClassroomResourceResponse;
import io.edupilot.classroom.dto.CreateClassroomLinkResourceRequest;
import io.edupilot.classroom.dto.UpdateClassroomResourceRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialProperties;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.material.storage.StorageException;
import io.edupilot.user.UserRole;

@Service
public class ClassroomResourceService {

	private static final Logger log = LoggerFactory.getLogger(
		ClassroomResourceService.class
	);
	private static final int TITLE_MAX_LENGTH = 200;
	private static final int FILE_NAME_MAX_LENGTH = 255;
	private static final int URL_MAX_LENGTH = 2048;
	private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
		"jpg", "jpeg", "png", "gif", "webp",
		"pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
		"hwp", "hwpx", "txt", "csv", "zip"
	);
	private static final Set<String> INLINE_EXTENSIONS = Set.of(
		"jpg", "jpeg", "png", "gif", "webp", "pdf"
	);

	private final ClassroomService classroomService;
	private final ClassroomResourceRepository resourceRepository;
	private final FileStorage fileStorage;
	private final MaterialProperties materialProperties;

	public ClassroomResourceService(
		ClassroomService classroomService,
		ClassroomResourceRepository resourceRepository,
		FileStorage fileStorage,
		MaterialProperties materialProperties
	) {
		this.classroomService = classroomService;
		this.resourceRepository = resourceRepository;
		this.fileStorage = fileStorage;
		this.materialProperties = materialProperties;
	}

	@Transactional
	public ClassroomResourceResponse createFile(
		Long userId,
		UserRole role,
		Long classroomId,
		MultipartFile file,
		String title,
		Integer weekNumber
	) {
		Classroom classroom = writableOwner(userId, role, classroomId);
		validateWeekNumber(classroom, weekNumber);
		String normalizedTitle = normalizedRequired(title, TITLE_MAX_LENGTH);
		String fileName = validateFile(file);

		String storagePath;
		try (InputStream inputStream = file.getInputStream()) {
			storagePath = fileStorage.storeClassroomResource(inputStream);
		} catch (IOException exception) {
			throw new StorageException("업로드 파일을 읽을 수 없습니다.", exception);
		}
		registerRollbackCleanup(storagePath);
		try {
			ClassroomResource resource = resourceRepository.saveAndFlush(
				ClassroomResource.file(
					classroom,
					normalizedTitle,
					weekNumber,
					fileName,
					file.getContentType(),
					file.getSize(),
					storagePath
				)
			);
			return ClassroomResourceResponse.from(resource);
		} catch (RuntimeException exception) {
			if (!TransactionSynchronizationManager.isSynchronizationActive()) {
				deleteStoredFile(storagePath, exception);
			}
			throw exception;
		}
	}

	@Transactional
	public ClassroomResourceResponse createLink(
		Long userId,
		UserRole role,
		Long classroomId,
		CreateClassroomLinkResourceRequest request
	) {
		Classroom classroom = writableOwner(userId, role, classroomId);
		validateWeekNumber(classroom, request.weekNumber());
		ClassroomResource resource = resourceRepository.saveAndFlush(
			ClassroomResource.link(
				classroom,
				normalizedRequired(request.title(), TITLE_MAX_LENGTH),
				request.weekNumber(),
				validateUrl(request.url())
			)
		);
		return ClassroomResourceResponse.from(resource);
	}

	@Transactional(readOnly = true)
	public ClassroomResourceListResponse list(
		Long userId,
		UserRole role,
		Long classroomId,
		Integer weekNumber,
		int page,
		int size
	) {
		Classroom classroom = classroomService.requireVisible(
			userId,
			role,
			classroomId
		);
		validateWeekNumber(classroom, weekNumber);
		PageRequest pageable = PageRequest.of(
			page,
			size,
			Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
		);
		Page<ClassroomResource> resources = weekNumber == null
			? resourceRepository.findByClassroom_Id(classroomId, pageable)
			: resourceRepository.findByClassroom_IdAndWeekNumber(
				classroomId,
				weekNumber,
				pageable
			);
		return ClassroomResourceListResponse.from(resources);
	}

	@Transactional
	public ClassroomResourceResponse update(
		Long userId,
		UserRole role,
		Long resourceId,
		UpdateClassroomResourceRequest request
	) {
		if (request == null
			|| !request.hasAnyField()
			|| request.isTitlePresent() && request.getTitle() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		ClassroomResource resource = writableResource(userId, role, resourceId);
		if (request.isWeekNumberPresent()) {
			validateWeekNumber(resource.getClassroom(), request.getWeekNumber());
		}
		resource.update(
			request.isTitlePresent()
				? normalizedRequired(request.getTitle(), TITLE_MAX_LENGTH)
				: null,
			request.isWeekNumberPresent(),
			request.getWeekNumber()
		);
		resourceRepository.flush();
		return ClassroomResourceResponse.from(resource);
	}

	@Transactional(readOnly = true)
	public ClassroomResourceFile file(
		Long userId,
		UserRole role,
		Long resourceId
	) {
		ClassroomResource resource = visibleResource(userId, role, resourceId);
		if (resource.getType() != ClassroomResourceType.FILE) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		return new ClassroomResourceFile(
			fileStorage.load(resource.getStoragePath()),
			resource.getFileName(),
			resource.getContentType(),
			INLINE_EXTENSIONS.contains(extension(resource.getFileName()))
		);
	}

	@Transactional
	public void delete(Long userId, UserRole role, Long resourceId) {
		ClassroomResource resource = writableResource(userId, role, resourceId);
		String storagePath = resource.getStoragePath();
		Long deletedResourceId = resource.getId();
		resourceRepository.delete(resource);
		resourceRepository.flush();
		if (storagePath != null) {
			registerAfterCommitDeletion(storagePath, deletedResourceId);
		}
	}

	private ClassroomResource visibleResource(
		Long userId,
		UserRole role,
		Long resourceId
	) {
		ClassroomResource resource = resourceRepository.findWithClassroom(resourceId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		classroomService.requireVisible(
			userId,
			role,
			resource.getClassroom().getId()
		);
		return resource;
	}

	private ClassroomResource writableResource(
		Long userId,
		UserRole role,
		Long resourceId
	) {
		ClassroomResource candidate = resourceRepository.findWithClassroom(resourceId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
		Classroom classroom = writableOwner(
			userId,
			role,
			candidate.getClassroom().getId()
		);
		return resourceRepository.findForUpdate(resourceId)
			.filter(resource -> resource.getClassroom().getId().equals(classroom.getId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	private Classroom writableOwner(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		Classroom classroom = classroomService.requireOwnerForUpdate(
			userId,
			role,
			classroomId
		);
		classroomService.assertWritable(classroom);
		return classroom;
	}

	private String validateFile(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (file.getSize() > materialProperties.uploadMaxBytes()) {
			throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
		}
		String fileName = StringUtils.getFilename(file.getOriginalFilename());
		if (fileName == null
			|| fileName.isBlank()
			|| fileName.length() > FILE_NAME_MAX_LENGTH
			|| !ALLOWED_EXTENSIONS.contains(extension(fileName))) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return fileName;
	}

	private String extension(String fileName) {
		String extension = StringUtils.getFilenameExtension(fileName);
		return extension == null ? "" : extension.toLowerCase(Locale.ROOT);
	}

	private String validateUrl(String value) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty() || normalized.length() > URL_MAX_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		try {
			URI uri = URI.create(normalized);
			String scheme = uri.getScheme();
			if (!("http".equalsIgnoreCase(scheme)
				|| "https".equalsIgnoreCase(scheme))
				|| uri.getHost() == null) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
			return normalized;
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private String normalizedRequired(String value, int maxLength) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty() || normalized.length() > maxLength) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}

	private void validateWeekNumber(Classroom classroom, Integer weekNumber) {
		if (weekNumber != null
			&& (weekNumber < 1 || weekNumber > classroom.getWeekCount())) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private void registerRollbackCleanup(String storagePath) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(
			new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					if (status != STATUS_COMMITTED) {
						try {
							fileStorage.delete(storagePath);
						} catch (RuntimeException ignored) {
							// 원래 롤백 원인을 보존합니다.
						}
					}
				}
			}
		);
	}

	private void registerAfterCommitDeletion(
		String storagePath,
		Long resourceId
	) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			deleteBestEffort(storagePath, resourceId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(
			new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					deleteBestEffort(storagePath, resourceId);
				}
			}
		);
	}

	private void deleteBestEffort(String storagePath, Long resourceId) {
		try {
			fileStorage.delete(storagePath);
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("resourceId", resourceId)
				.setCause(exception)
				.log("Classroom resource file cleanup failed");
		}
	}

	private void deleteStoredFile(String storagePath, RuntimeException cause) {
		try {
			fileStorage.delete(storagePath);
		} catch (RuntimeException cleanupFailure) {
			cause.addSuppressed(cleanupFailure);
		}
	}
}
