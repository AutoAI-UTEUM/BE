package io.edupilot.material;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.classroom.ClassroomWeekService;
import io.edupilot.material.dto.MaterialDetailResponse;
import io.edupilot.material.dto.MaterialListResponse;
import io.edupilot.material.dto.MaterialPageResponse;
import io.edupilot.material.dto.MaterialSummaryResponse;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.material.storage.StorageException;
import io.edupilot.notification.NotificationTriggerService;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@Service
public class MaterialService {

	private static final byte[] PDF_MAGIC = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
	private static final int TITLE_MAX_LENGTH = 255;

	private final LearningMaterialRepository materialRepository;
	private final MaterialPageRepository pageRepository;
	private final UserRepository userRepository;
	private final FileStorage fileStorage;
	private final MaterialProperties properties;
	private final MaterialDeletionGuard deletionGuard;
	private final ApplicationEventPublisher eventPublisher;
	private final MaterialAccessService accessService;
	private final ClassroomWeekService weekService;
	private final NotificationTriggerService notificationTriggerService;

	public MaterialService(
		LearningMaterialRepository materialRepository,
		MaterialPageRepository pageRepository,
		UserRepository userRepository,
		FileStorage fileStorage,
		MaterialProperties properties,
		MaterialDeletionGuard deletionGuard,
		ApplicationEventPublisher eventPublisher,
		MaterialAccessService accessService,
		ClassroomWeekService weekService,
		NotificationTriggerService notificationTriggerService
	) {
		this.materialRepository = materialRepository;
		this.pageRepository = pageRepository;
		this.userRepository = userRepository;
		this.fileStorage = fileStorage;
		this.properties = properties;
		this.deletionGuard = deletionGuard;
		this.eventPublisher = eventPublisher;
		this.accessService = accessService;
		this.weekService = weekService;
		this.notificationTriggerService = notificationTriggerService;
	}

	@Transactional
	public MaterialSummaryResponse upload(
		Long ownerId,
		MultipartFile file,
		String title
	) {
		return upload(ownerId, UserRole.LEARNER, file, title, null, null);
	}

	@Transactional
	public MaterialSummaryResponse upload(
		Long ownerId,
		UserRole role,
		MultipartFile file,
		String title,
		Long classroomId,
		Integer weekNumber
	) {
		validateClassroomParts(classroomId, weekNumber);
		validateFile(file);
		String normalizedTitle = validateTitle(title);

		String storageKey;
		try (InputStream inputStream = file.getInputStream()) {
			storageKey = fileStorage.store(inputStream);
		} catch (IOException exception) {
			throw new StorageException("업로드 파일을 읽을 수 없습니다.", exception);
		}
		registerRollbackCleanup(storageKey);
		try {
			User owner = userRepository.getReferenceById(ownerId);
			LearningMaterial material = materialRepository.saveAndFlush(
				LearningMaterial.create(owner, normalizedTitle, storageKey)
			);
			if (classroomId != null) {
				weekService.linkUploadedMaterial(
					ownerId,
					role,
					classroomId,
					weekNumber,
					material
				);
				notificationTriggerService.materialUploaded(
					classroomId,
					material.getId(),
					material.getTitle()
				);
			}
			eventPublisher.publishEvent(new MaterialExtractionRequested(
				material.getId(),
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			));
			return MaterialSummaryResponse.from(material);
		} catch (RuntimeException exception) {
			if (!TransactionSynchronizationManager.isSynchronizationActive()) {
				deleteStoredFile(storageKey, exception);
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public MaterialListResponse list(Long ownerId, int page, int size) {
		PageRequest pageable = PageRequest.of(
			page,
			size,
			Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
		);
		Page<LearningMaterial> materials = materialRepository.findByOwner_IdAndStatus(
			ownerId,
			MaterialStatus.ACTIVE,
			pageable
		);
		return MaterialListResponse.from(materials);
	}

	@Transactional(readOnly = true)
	public MaterialDetailResponse detail(Long ownerId, Long materialId) {
		return MaterialDetailResponse.from(accessService.requireAccessible(
			ownerId,
			materialId
		));
	}

	@Transactional(readOnly = true)
	public MaterialFile file(Long ownerId, Long materialId) {
		LearningMaterial material = accessService.requireAccessible(ownerId, materialId);
		return new MaterialFile(material.getId(), fileStorage.load(material.getStorageKey()));
	}

	@Transactional(readOnly = true)
	public MaterialPageResponse page(
		Long ownerId,
		Long materialId,
		int pageNumber
	) {
		LearningMaterial material = accessService.requireAccessible(ownerId, materialId);
		if (material.getProcessingStatus() == MaterialProcessingStatus.PROCESSING) {
			throw new BusinessException(ErrorCode.MATERIAL_PROCESSING);
		}
		if (material.getProcessingStatus() == MaterialProcessingStatus.FAILED) {
			throw new BusinessException(ErrorCode.MATERIAL_PROCESSING_FAILED);
		}
		if (pageNumber < 1
			|| material.getPageCount() == null
			|| pageNumber > material.getPageCount()) {
			throw new BusinessException(ErrorCode.PAGE_OUT_OF_RANGE);
		}

		MaterialPage page = pageRepository.findByMaterial_IdAndPageNumber(
			materialId,
			pageNumber
		).orElseThrow(() -> new BusinessException(ErrorCode.PAGE_OUT_OF_RANGE));
		return MaterialPageResponse.from(page);
	}

	@Transactional
	public void delete(Long ownerId, Long materialId) {
		LearningMaterial material = materialRepository.findByIdForUpdate(materialId)
			.filter(LearningMaterial::isActive)
			.filter(candidate -> candidate.getOwnerId().equals(ownerId))
			.orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		deletionGuard.assertDeletable(materialId);
		material.delete();
	}

	private void validateFile(MultipartFile file) {
		if (file == null || file.getSize() > properties.uploadMaxBytes()) {
			throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
		}
		if (!MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(file.getContentType())) {
			throw new BusinessException(ErrorCode.INVALID_PDF_FILE);
		}

		try (InputStream inputStream = file.getInputStream()) {
			byte[] magic = inputStream.readNBytes(PDF_MAGIC.length);
			if (!Arrays.equals(magic, PDF_MAGIC)) {
				throw new BusinessException(ErrorCode.INVALID_PDF_FILE);
			}
		} catch (IOException exception) {
			throw new BusinessException(ErrorCode.INVALID_PDF_FILE);
		}
	}

	private String validateTitle(String title) {
		String normalized = title == null ? "" : title.trim();
		if (normalized.isEmpty() || normalized.length() > TITLE_MAX_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}

	private void validateClassroomParts(Long classroomId, Integer weekNumber) {
		if ((classroomId == null) != (weekNumber == null)
			|| weekNumber != null && weekNumber < 1) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private void registerRollbackCleanup(String storageKey) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(
			new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					if (status != STATUS_COMMITTED) {
						try {
							fileStorage.delete(storageKey);
						} catch (RuntimeException ignored) {
							// 원래 롤백 원인을 보존합니다.
						}
					}
				}
			}
		);
	}

	private void deleteStoredFile(String storageKey, RuntimeException cause) {
		try {
			fileStorage.delete(storageKey);
		} catch (RuntimeException cleanupFailure) {
			cause.addSuppressed(cleanupFailure);
		}
	}
}
