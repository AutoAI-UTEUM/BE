package io.edupilot.classroom;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.user.UserRole;

@Service
public class ClassroomWeekService {

	private final ClassroomService classroomService;
	private final ClassroomWeekRepository weekRepository;
	private final ClassroomWeekMaterialRepository weekMaterialRepository;
	private final LearningMaterialRepository materialRepository;
	private final ClassroomMemberRepository memberRepository;
	private final LearningProgressService progressService;
	private final LearningSessionRepository sessionRepository;
	private final Clock clock;

	public ClassroomWeekService(
		ClassroomService classroomService,
		ClassroomWeekRepository weekRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		LearningMaterialRepository materialRepository,
		ClassroomMemberRepository memberRepository,
		LearningProgressService progressService,
		LearningSessionRepository sessionRepository,
		Clock clock
	) {
		this.classroomService = classroomService;
		this.weekRepository = weekRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.materialRepository = materialRepository;
		this.memberRepository = memberRepository;
		this.progressService = progressService;
		this.sessionRepository = sessionRepository;
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
		var weeks = weekRepository
			.findByClassroom_IdOrderByWeekNumberAsc(classroomId)
			.stream()
			.filter(week -> ownerView || week.isReleased(now))
			.toList();
		return new ClassroomWeekListResponse(responses(classroomId, weeks));
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
			ClassroomWeek week = weekRepository.saveAndFlush(ClassroomWeek.create(
				classroom,
				request.weekNumber(),
				title,
				request.releaseAt()
			));
			return response(classroomId, week);
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
		return response(classroomId, week);
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
		return response(classroomId, week);
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
		return response(classroomId, week);
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

	private ClassroomWeekResponse response(Long classroomId, ClassroomWeek week) {
		return responses(classroomId, List.of(week)).get(0);
	}

	private List<ClassroomWeekResponse> responses(
		Long classroomId,
		List<ClassroomWeek> weeks
	) {
		if (weeks.isEmpty()) {
			return List.of();
		}
		var linksByWeekId = weekMaterialRepository.findByWeekIds(
			weeks.stream().map(ClassroomWeek::getId).toList()
		).stream().collect(Collectors.groupingBy(
			ClassroomWeekMaterial::getWeekId,
			LinkedHashMap::new,
			Collectors.toList()
		));
		Map<Long, LearningMaterial> distinctMaterials = linksByWeekId.values()
			.stream()
			.flatMap(List::stream)
			.map(ClassroomWeekMaterial::getMaterial)
			.collect(Collectors.toMap(
				LearningMaterial::getId,
				Function.identity(),
				(left, right) -> left,
				LinkedHashMap::new
			));
		long learnerCount = memberRepository.countByClassroom_Id(classroomId);
		var progress = distinctMaterials.isEmpty()
			? new LearningProgressService.ClassroomProgressSnapshot(
				learnerCount,
				Map.of()
			)
			: progressService.calculateClassroomProgressSnapshot(
				classroomId,
				distinctMaterials.values(),
				learnerCount
			);
		Map<Long, Long> viewersByMaterial = new LinkedHashMap<>();
		if (!distinctMaterials.isEmpty()) {
			for (var count : sessionRepository.findMaterialViewerCounts(
				classroomId,
				distinctMaterials.keySet(),
				List.of(SessionStatus.ACTIVE, SessionStatus.COMPLETED)
			)) {
				viewersByMaterial.put(count.getMaterialId(), count.getViewerCount());
			}
		}
		return weeks.stream().map(week -> {
			var materials = linksByWeekId.getOrDefault(week.getId(), List.of())
				.stream()
				.map(ClassroomWeekMaterial::getMaterial)
				.toList();
			return new ClassroomWeekResponse(
				week.getWeekNumber(),
				week.getTitle(),
				week.statusAt(clock.instant()),
				week.getReleaseAt(),
				progress.averageProgressRate(materials),
				materials.stream().map(material -> {
					long viewerCount = viewersByMaterial.getOrDefault(
						material.getId(),
						0L
					);
					return ClassroomWeekMaterialResponse.from(
						material,
						viewerCount,
						roundedRate(viewerCount, learnerCount)
					);
				}).toList()
			);
		}).toList();
	}

	private int roundedRate(long numerator, long denominator) {
		if (numerator == 0 || denominator < 1) {
			return 0;
		}
		return (int) Math.round(numerator * 100.0 / denominator);
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
