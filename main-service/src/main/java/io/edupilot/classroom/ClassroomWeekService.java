package io.edupilot.classroom;

import java.time.Clock;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.dto.ClassroomWeekListResponse;
import io.edupilot.classroom.dto.ClassroomWeekMaterialResponse;
import io.edupilot.classroom.dto.ClassroomWeekResponse;
import io.edupilot.classroom.dto.CreateClassroomWeekRequest;
import io.edupilot.classroom.dto.UpdateClassroomWeekRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialStatus;
import io.edupilot.user.UserRole;

@Service
public class ClassroomWeekService {

	private final ClassroomService classroomService;
	private final ClassroomWeekRepository weekRepository;
	private final ClassroomWeekMaterialRepository weekMaterialRepository;
	private final LearningMaterialRepository materialRepository;
	private final Clock clock;

	public ClassroomWeekService(
		ClassroomService classroomService,
		ClassroomWeekRepository weekRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		LearningMaterialRepository materialRepository,
		Clock clock
	) {
		this.classroomService = classroomService;
		this.weekRepository = weekRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.materialRepository = materialRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ClassroomWeekListResponse list(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		Classroom classroom = classroomService.requireVisible(
			userId,
			role,
			classroomId
		);
		boolean ownerView = classroom.getInstructorId().equals(userId);
		var now = clock.instant();
		List<ClassroomWeekResponse> items = weekRepository
			.findByClassroom_IdOrderByDisplayOrderAscIdAsc(classroomId)
			.stream()
			.filter(week -> ownerView || week.isVisibleToLearner(now))
			.map(this::response)
			.toList();
		return new ClassroomWeekListResponse(items);
	}

	@Transactional
	public ClassroomWeekResponse create(
		Long userId,
		UserRole role,
		Long classroomId,
		CreateClassroomWeekRequest request
	) {
		Classroom classroom = writableOwner(userId, role, classroomId);
		validateWeekNumber(classroom, request.weekNumber());
		String title = validateTitle(request.title());
		if (weekRepository.existsByClassroom_IdAndWeekNumber(
			classroomId,
			request.weekNumber()
		)) {
			throw new BusinessException(ErrorCode.WEEK_ALREADY_EXISTS);
		}
		try {
			var now = clock.instant();
			ClassroomWeekStatus status = request.releaseAt() == null
				|| !request.releaseAt().isAfter(now)
				? ClassroomWeekStatus.PUBLISHED
				: ClassroomWeekStatus.SCHEDULED;
			Integer maximumDisplayOrder = weekRepository.findMaximumDisplayOrder(
				classroomId
			);
			ClassroomWeek week = weekRepository.saveAndFlush(ClassroomWeek.create(
				classroom,
				request.weekNumber(),
				title,
				request.releaseAt(),
				status,
				maximumDisplayOrder == null ? 1 : maximumDisplayOrder + 1
			));
			return response(week);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.WEEK_ALREADY_EXISTS);
		}
	}

	@Transactional
	public ClassroomWeekResponse update(
		Long userId,
		UserRole role,
		Long classroomId,
		int weekNumber,
		UpdateClassroomWeekRequest request
	) {
		writableOwner(userId, role, classroomId);
		if (!request.hasAnyField()
			|| request.isTitlePresent() && request.getTitle() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		ClassroomWeek week = ownedWeekForUpdate(classroomId, weekNumber);
		week.update(
			request.isTitlePresent(),
			request.isTitlePresent() ? validateTitle(request.getTitle()) : null,
			request.isReleaseAtPresent(),
			request.getReleaseAt()
		);
		weekRepository.flush();
		return response(week);
	}

	@Transactional
	public void delete(
		Long userId,
		UserRole role,
		Long classroomId,
		int weekNumber
	) {
		writableOwner(userId, role, classroomId);
		ClassroomWeek week = ownedWeekForUpdate(classroomId, weekNumber);
		weekMaterialRepository.deleteByWeek_Id(week.getId());
		weekRepository.delete(week);
	}

	@Transactional
	public ClassroomWeekResponse link(
		Long userId,
		UserRole role,
		Long classroomId,
		int weekNumber,
		Long materialId
	) {
		writableOwner(userId, role, classroomId);
		ClassroomWeek week = ownedWeekForUpdate(classroomId, weekNumber);
		LearningMaterial material = materialRepository
			.findByIdAndOwner_IdAndStatus(
				materialId,
				userId,
				MaterialStatus.ACTIVE
			)
			.orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		link(week, material);
		return response(week);
	}

	@Transactional
	public ClassroomWeekResponse unlink(
		Long userId,
		UserRole role,
		Long classroomId,
		int weekNumber,
		Long materialId
	) {
		writableOwner(userId, role, classroomId);
		ClassroomWeek week = ownedWeekForUpdate(classroomId, weekNumber);
		weekMaterialRepository.deleteByWeek_IdAndMaterial_Id(
			week.getId(),
			materialId
		);
		return response(week);
	}

	@Transactional
	public void linkUploadedMaterial(
		Long userId,
		UserRole role,
		Long classroomId,
		int weekNumber,
		LearningMaterial material
	) {
		writableOwner(userId, role, classroomId);
		ClassroomWeek week = ownedWeekForUpdate(classroomId, weekNumber);
		link(week, material);
	}

	private void link(ClassroomWeek week, LearningMaterial material) {
		if (weekMaterialRepository.existsByWeek_IdAndMaterial_Id(
			week.getId(),
			material.getId()
		)) {
			throw new BusinessException(ErrorCode.MATERIAL_ALREADY_LINKED);
		}
		try {
			weekMaterialRepository.saveAndFlush(ClassroomWeekMaterial.create(
				week,
				material,
				clock.instant()
			));
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.MATERIAL_ALREADY_LINKED);
		}
	}

	private Classroom writableOwner(Long userId, UserRole role, Long classroomId) {
		Classroom classroom = classroomService.requireOwnerForUpdate(
			userId,
			role,
			classroomId
		);
		classroomService.assertWritable(classroom);
		return classroom;
	}

	private ClassroomWeek ownedWeekForUpdate(Long classroomId, int weekNumber) {
		return weekRepository.findForUpdate(classroomId, weekNumber)
			.orElseThrow(() -> new BusinessException(ErrorCode.WEEK_NOT_FOUND));
	}

	private ClassroomWeekResponse response(ClassroomWeek week) {
		return new ClassroomWeekResponse(
			week.getWeekNumber(),
			week.getTitle(),
			week.getStatus(),
			week.getReleaseAt(),
			weekMaterialRepository.findByWeek_IdOrderByAddedAtAscIdAsc(week.getId())
				.stream()
				.map(link -> ClassroomWeekMaterialResponse.from(link.getMaterial()))
				.toList()
		);
	}

	private void validateWeekNumber(Classroom classroom, int weekNumber) {
		if (weekNumber < 1 || weekNumber > classroom.getWeekCount()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private String validateTitle(String title) {
		String normalized = title == null ? "" : title.trim();
		if (normalized.isEmpty() || normalized.length() > 100) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}
}
