package io.edupilot.classroom;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.dto.ClassroomDetailResponse;
import io.edupilot.classroom.dto.ClassroomJoinRequestListResponse;
import io.edupilot.classroom.dto.ClassroomLastStudiedResponse;
import io.edupilot.classroom.dto.ClassroomListResponse;
import io.edupilot.classroom.dto.ClassroomSummaryResponse;
import io.edupilot.classroom.dto.CreateClassroomRequest;
import io.edupilot.classroom.dto.CreateJoinRequest;
import io.edupilot.classroom.dto.InviteCodeResponse;
import io.edupilot.classroom.dto.JoinRequestListResponse;
import io.edupilot.classroom.dto.JoinRequestProcessResponse;
import io.edupilot.classroom.dto.JoinRequestResponse;
import io.edupilot.classroom.dto.PermanentDeleteClassroomRequest;
import io.edupilot.classroom.dto.UpdateClassroomRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialProcessingStatus;
import io.edupilot.material.MaterialStatus;
import io.edupilot.notification.NotificationTriggerService;
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@Service
public class ClassroomService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final int INVITE_CODE_ATTEMPTS = 10;

	private final ClassroomRepository classroomRepository;
	private final ClassroomMemberRepository memberRepository;
	private final ClassroomJoinRequestRepository joinRequestRepository;
	private final ClassroomWeekRepository weekRepository;
	private final ClassroomWeekMaterialRepository weekMaterialRepository;
	private final ClassroomPermanentDeleteRepository permanentDeleteRepository;
	private final LearningProgressService progressService;
	private final LearningSessionRepository sessionRepository;
	private final UserRepository userRepository;
	private final ClassroomInviteCodeGenerator inviteCodeGenerator;
	private final NotificationTriggerService notificationTriggerService;
	private final Clock clock;

	public ClassroomService(
		ClassroomRepository classroomRepository,
		ClassroomMemberRepository memberRepository,
		ClassroomJoinRequestRepository joinRequestRepository,
		ClassroomWeekRepository weekRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		ClassroomPermanentDeleteRepository permanentDeleteRepository,
		LearningProgressService progressService,
		LearningSessionRepository sessionRepository,
		UserRepository userRepository,
		ClassroomInviteCodeGenerator inviteCodeGenerator,
		NotificationTriggerService notificationTriggerService,
		Clock clock
	) {
		this.classroomRepository = classroomRepository;
		this.memberRepository = memberRepository;
		this.joinRequestRepository = joinRequestRepository;
		this.weekRepository = weekRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.permanentDeleteRepository = permanentDeleteRepository;
		this.progressService = progressService;
		this.sessionRepository = sessionRepository;
		this.userRepository = userRepository;
		this.inviteCodeGenerator = inviteCodeGenerator;
		this.notificationTriggerService = notificationTriggerService;
		this.clock = clock;
	}

	@Transactional
	public ClassroomDetailResponse create(
		Long userId,
		UserRole role,
		CreateClassroomRequest request
	) {
		requireInstructor(role);
		if (request.color() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		String name = normalizedRequired(request.name(), 100);
		String description = normalizedOptional(request.description(), 255);
		validateDates(request.startDate(), request.endDate());
		User instructor = activeUser(userId);
		Classroom classroom = classroomRepository.saveAndFlush(Classroom.create(
			instructor,
			name,
			request.startDate(),
			request.endDate(),
			request.color(),
			description,
			uniqueInviteCode()
		));
		return detailResponse(userId, classroom, true);
	}

	@Transactional(readOnly = true)
	public ClassroomListResponse list(
		Long userId,
		UserRole role,
		ClassroomStatus status,
		String query,
		ClassroomSort sort,
		int page,
		int size
	) {
		requireClassroomRole(role);
		PageRequest pageable = PageRequest.of(page, size, classroomSort(sort));
		Page<Classroom> classrooms = classroomRepository.findVisibleByUserId(
			userId,
			status,
			normalizedQuery(query),
			pageable
		);
		var classroomIds = classrooms.getContent().stream()
			.map(Classroom::getId)
			.toList();
		Map<Long, ClassroomWeekMaterialRepository.ClassroomMaterialCount>
			materialCounts = classroomIds.isEmpty()
				? Map.of()
				: weekMaterialRepository.countDistinctMaterialsByClassroomIds(
					classroomIds
				).stream().collect(Collectors.toMap(
					ClassroomWeekMaterialRepository.ClassroomMaterialCount::getClassroomId,
					Function.identity()
				));
		Page<ClassroomSummaryResponse> responses = classrooms.map(classroom -> {
			boolean ownerView = classroom.getInstructorId().equals(userId);
			ClassroomLearnerMetrics metrics = learnerMetrics(
				userId,
				classroom,
				ownerView
			);
			return ClassroomSummaryResponse.from(
				classroom,
				ownerView,
				currentWeek(classroom),
				memberRepository.countByClassroom_Id(classroom.getId()),
				materialCounts.containsKey(classroom.getId())
					? materialCounts.get(classroom.getId()).getMaterialCount()
					: 0,
				ownerView ? pendingCount(classroom.getId()) : 0,
				metrics.progressRate(),
				metrics.lastStudied()
			);
		});
		return new ClassroomListResponse(
			responses.getContent(),
			responses.getNumber(),
			responses.getSize(),
			responses.getTotalElements(),
			responses.getTotalPages()
		);
	}

	@Transactional(readOnly = true)
	public ClassroomDetailResponse detail(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		requireClassroomRole(role);
		Classroom classroom = visibleClassroom(userId, classroomId);
		return detailResponse(
			userId,
			classroom,
			classroom.getInstructorId().equals(userId)
		);
	}

	@Transactional
	public ClassroomDetailResponse update(
		Long userId,
		UserRole role,
		Long classroomId,
		UpdateClassroomRequest request
	) {
		Classroom classroom = ownedClassroomForUpdate(userId, role, classroomId);
		assertActive(classroom);
		validateUpdate(request, classroom);
		long startDateShiftDays = request.isStartDatePresent()
			? ChronoUnit.DAYS.between(
				classroom.getStartDate(),
				request.getStartDate()
			)
			: 0;
		if (Boolean.TRUE.equals(request.getShiftWeekReleaseDates())
			&& startDateShiftDays != 0) {
			weekRepository.findAllForUpdateByClassroomId(classroomId)
				.forEach(week -> week.shiftReleaseAt(startDateShiftDays));
		}
		classroom.update(
			request.isNamePresent() ? normalizedRequired(request.getName(), 100) : null,
			request.isStartDatePresent() ? request.getStartDate() : null,
			request.isEndDatePresent() ? request.getEndDate() : null,
			request.isColorPresent() ? request.getColor() : null,
			request.isDescriptionPresent(),
			request.isDescriptionPresent()
				? normalizedOptional(request.getDescription(), 255)
				: null
		);
		classroomRepository.flush();
		return detailResponse(userId, classroom, true);
	}

	@Transactional
	public ClassroomDetailResponse complete(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		Classroom classroom = ownedClassroomForUpdate(userId, role, classroomId);
		classroom.complete();
		classroomRepository.flush();
		return detailResponse(userId, classroom, true);
	}

	@Transactional
	public void deletePermanently(
		Long userId,
		UserRole role,
		Long classroomId,
		PermanentDeleteClassroomRequest request
	) {
		if (role != UserRole.INSTRUCTOR) {
			throw new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND);
		}
		Classroom classroom = requireStrictOwnerForUpdate(
			userId, role, classroomId
		);
		String confirmName = request == null || request.confirmName() == null
			? null
			: request.confirmName().trim();
		if (!classroom.getName().equals(confirmName)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}

		permanentDeleteRepository.deleteExamAnswers(classroomId);
		permanentDeleteRepository.deleteExamSubmissions(classroomId);
		permanentDeleteRepository.deleteExamQuestions(classroomId);
		permanentDeleteRepository.deleteExams(classroomId);
		permanentDeleteRepository.deleteReportCriterionResults(classroomId);
		permanentDeleteRepository.clearStudentReportPreviousReferences(classroomId);
		permanentDeleteRepository.deleteStudentReports(classroomId);
		permanentDeleteRepository.deleteReportEvidenceSnapshots(classroomId);
		permanentDeleteRepository.deleteReportGenerations(classroomId);
		permanentDeleteRepository.deleteReportCriteria(classroomId);
		permanentDeleteRepository.deleteClassroomResources(classroomId);
		permanentDeleteRepository.deleteClassroomNotices(classroomId);
		permanentDeleteRepository.deleteClassroomWeekMaterials(classroomId);
		permanentDeleteRepository.deleteClassroomWeeks(classroomId);
		permanentDeleteRepository.deleteClassroomJoinRequests(classroomId);
		permanentDeleteRepository.deleteClassroomMembers(classroomId);
		permanentDeleteRepository.deleteClassroom(classroomId);
	}

	@Transactional(readOnly = true)
	public InviteCodeResponse inviteCode(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		Classroom classroom = ownedClassroom(userId, role, classroomId);
		return inviteCodeResponse(classroom);
	}

	@Transactional
	public InviteCodeResponse regenerateInviteCode(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		Classroom classroom = ownedClassroomForUpdate(userId, role, classroomId);
		assertActive(classroom);
		classroom.regenerateInviteCode(uniqueInviteCode());
		classroomRepository.flush();
		return inviteCodeResponse(classroom);
	}

	@Transactional
	public JoinRequestResponse requestJoin(
		Long userId,
		UserRole role,
		CreateJoinRequest request
	) {
		requireClassroomRole(role);
		String normalizedCode = normalizeInviteCode(request.inviteCode());
		Classroom classroom = classroomRepository.findByInviteCode(normalizedCode)
			.filter(candidate -> candidate.getStatus() == ClassroomStatus.ACTIVE)
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INVITE_CODE));
		if (classroom.getInstructorId().equals(userId)) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		if (memberRepository.existsByClassroom_IdAndUser_Id(classroom.getId(), userId)) {
			throw new BusinessException(ErrorCode.ALREADY_CLASSROOM_MEMBER);
		}

		var existing = joinRequestRepository.findByClassroomAndUserForUpdate(
			classroom.getId(),
			userId
		);
		if (existing.isPresent()) {
			ClassroomJoinRequest joinRequest = existing.get();
			if (joinRequest.getStatus() == ClassroomJoinRequestStatus.PENDING) {
				throw new BusinessException(ErrorCode.JOIN_REQUEST_ALREADY_PENDING);
			}
			if (joinRequest.getStatus() == ClassroomJoinRequestStatus.APPROVED) {
				throw new BusinessException(ErrorCode.ALREADY_CLASSROOM_MEMBER);
			}
			joinRequest.requestAgain(clock.instant());
			joinRequestRepository.flush();
			notificationTriggerService.joinRequestReceived(joinRequest);
			return JoinRequestResponse.from(joinRequest);
		}

		User user = activeUser(userId);
		ClassroomJoinRequest created = joinRequestRepository.saveAndFlush(
			ClassroomJoinRequest.create(classroom, user, clock.instant())
		);
		notificationTriggerService.joinRequestReceived(created);
		return JoinRequestResponse.from(created);
	}

	@Transactional(readOnly = true)
	public JoinRequestListResponse myJoinRequests(
		Long userId,
		UserRole role,
		int page,
		int size
	) {
		requireClassroomRole(role);
		PageRequest pageable = PageRequest.of(
			page,
			size,
			Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"))
		);
		return JoinRequestListResponse.from(
			joinRequestRepository.findByUser_Id(userId, pageable)
		);
	}

	@Transactional(readOnly = true)
	public ClassroomJoinRequestListResponse joinRequests(
		Long userId,
		UserRole role,
		Long classroomId,
		ClassroomJoinRequestStatus status,
		int page,
		int size
	) {
		ownedClassroom(userId, role, classroomId);
		PageRequest pageable = PageRequest.of(
			page,
			size,
			Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"))
		);
		return ClassroomJoinRequestListResponse.from(
			joinRequestRepository.findByClassroom_IdAndStatus(
				classroomId,
				status,
				pageable
			)
		);
	}

	@Transactional
	public JoinRequestProcessResponse approve(
		Long userId,
		UserRole role,
		Long classroomId,
		Long requestId
	) {
		Classroom classroom = ownedClassroomForUpdate(userId, role, classroomId);
		assertActive(classroom);
		ClassroomJoinRequest request = pendingRequest(classroomId, requestId);
		if (memberRepository.existsByClassroom_IdAndUser_Id(
			classroomId,
			request.getUser().getId()
		)) {
			throw new BusinessException(ErrorCode.ALREADY_CLASSROOM_MEMBER);
		}
		var now = clock.instant();
		memberRepository.save(ClassroomMember.create(
			classroom,
			request.getUser(),
			now
		));
		request.approve(now);
		joinRequestRepository.flush();
		notificationTriggerService.joinRequestProcessed(request);
		return JoinRequestProcessResponse.from(request);
	}

	@Transactional
	public JoinRequestProcessResponse reject(
		Long userId,
		UserRole role,
		Long classroomId,
		Long requestId
	) {
		Classroom classroom = ownedClassroomForUpdate(userId, role, classroomId);
		assertActive(classroom);
		ClassroomJoinRequest request = pendingRequest(classroomId, requestId);
		request.reject(clock.instant());
		joinRequestRepository.flush();
		notificationTriggerService.joinRequestProcessed(request);
		return JoinRequestProcessResponse.from(request);
	}

	private ClassroomJoinRequest pendingRequest(Long classroomId, Long requestId) {
		ClassroomJoinRequest request = joinRequestRepository.findForUpdate(
			classroomId,
			requestId
		).orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		if (request.getStatus() != ClassroomJoinRequestStatus.PENDING) {
			throw new BusinessException(ErrorCode.JOIN_REQUEST_ALREADY_PROCESSED);
		}
		return request;
	}

	private ClassroomDetailResponse detailResponse(
		Long userId,
		Classroom classroom,
		boolean ownerView
	) {
		ClassroomLearnerMetrics metrics = learnerMetrics(
			userId,
			classroom,
			ownerView
		);
		return ClassroomDetailResponse.from(
			classroom,
			ownerView,
			currentWeek(classroom),
			memberRepository.countByClassroom_Id(classroom.getId()),
			ownerView ? pendingCount(classroom.getId()) : 0,
			metrics.progressRate(),
			metrics.lastStudied()
		);
	}

	private ClassroomLearnerMetrics learnerMetrics(
		Long userId,
		Classroom classroom,
		boolean ownerView
	) {
		if (ownerView) {
			return new ClassroomLearnerMetrics(null, null);
		}
		var materials = weekMaterialRepository.findDistinctReadyMaterials(
			classroom.getId(),
			MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY
		);
		var materialIds = materials.stream().map(material -> material.getId()).toList();
		ClassroomLastStudiedResponse lastStudied = materialIds.isEmpty()
			? null
			: sessionRepository
				.findFirstByUser_IdAndMaterial_IdInAndStatusInOrderByUpdatedAtDescIdDesc(
					userId,
					materialIds,
					java.util.List.of(SessionStatus.ACTIVE, SessionStatus.COMPLETED)
				)
				.map(ClassroomLastStudiedResponse::from)
				.orElse(null);
		return new ClassroomLearnerMetrics(
			progressService.calculateClassroomProgressRate(userId, classroom.getId()),
			lastStudied
		);
	}

	private long pendingCount(Long classroomId) {
		return joinRequestRepository.countByClassroom_IdAndStatus(
			classroomId,
			ClassroomJoinRequestStatus.PENDING
		);
	}

	private int currentWeek(Classroom classroom) {
		LocalDate today = LocalDate.now(clock.withZone(SEOUL));
		if (today.isBefore(classroom.getStartDate())) {
			return 1;
		}
		long elapsedDays = ChronoUnit.DAYS.between(classroom.getStartDate(), today);
		return Math.min(classroom.getWeekCount(), Math.toIntExact(elapsedDays / 7 + 1));
	}

	private Classroom visibleClassroom(Long userId, Long classroomId) {
		Classroom classroom = classroomRepository.findWithInstructorById(classroomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		if (classroom.getInstructorId().equals(userId)
			|| memberRepository.existsByClassroom_IdAndUser_Id(classroomId, userId)) {
			return classroom;
		}
		throw new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND);
	}

	private Classroom ownedClassroom(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		requireInstructor(role);
		Classroom classroom = classroomRepository.findWithInstructorById(classroomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		return assertOwnerOrHide(userId, classroom);
	}

	private Classroom ownedClassroomForUpdate(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		requireInstructor(role);
		Classroom classroom = classroomRepository.findByIdForUpdate(classroomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		return assertOwnerOrHide(userId, classroom);
	}

	private Classroom assertOwnerOrHide(Long userId, Classroom classroom) {
		if (classroom.getInstructorId().equals(userId)) {
			return classroom;
		}
		if (memberRepository.existsByClassroom_IdAndUser_Id(classroom.getId(), userId)) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		throw new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND);
	}

	private void validateUpdate(
		UpdateClassroomRequest request,
		Classroom classroom
	) {
		if (!request.hasAnyField()
			|| request.isNamePresent() && request.getName() == null
			|| request.isStartDatePresent() && request.getStartDate() == null
			|| request.isEndDatePresent() && request.getEndDate() == null
			|| request.isShiftWeekReleaseDatesPresent()
				&& request.getShiftWeekReleaseDates() == null
			|| request.isColorPresent() && request.getColor() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (request.isStartDatePresent() || request.isEndDatePresent()) {
			LocalDate startDate = request.isStartDatePresent()
				? request.getStartDate()
				: classroom.getStartDate();
			LocalDate endDate = request.isEndDatePresent()
				? request.getEndDate()
				: classroom.getEndDate();
			validateDates(startDate, endDate);
			long inclusiveDays = ChronoUnit.DAYS.between(
				startDate,
				endDate
			) + 1;
			int newWeekCount = Math.toIntExact((inclusiveDays + 6) / 7);
			Integer maximumWeekNumber = weekRepository.findMaximumWeekNumber(
				classroom.getId()
			);
			if (maximumWeekNumber != null && maximumWeekNumber > newWeekCount) {
				throw new BusinessException(
					ErrorCode.CLASSROOM_WEEK_RANGE_CONFLICT
				);
			}
		}
	}

	@Transactional(readOnly = true)
	public Classroom requireVisible(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		requireClassroomRole(role);
		return visibleClassroom(userId, classroomId);
	}

	@Transactional(readOnly = true)
	public Classroom requireOwner(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		return ownedClassroom(userId, role, classroomId);
	}

	@Transactional(readOnly = true)
	public Classroom requireStrictOwner(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		requireInstructor(role);
		return classroomRepository.findWithInstructorById(classroomId)
			.filter(classroom -> classroom.getInstructorId().equals(userId))
			.orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
	}

	@Transactional
	public Classroom requireOwnerForUpdate(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		return ownedClassroomForUpdate(userId, role, classroomId);
	}

	@Transactional
	public Classroom requireStrictOwnerForUpdate(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		requireInstructor(role);
		return classroomRepository.findByIdForUpdate(classroomId)
			.filter(classroom -> classroom.getInstructorId().equals(userId))
			.orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
	}

	public void assertWritable(Classroom classroom) {
		assertActive(classroom);
	}

	private void validateDates(LocalDate startDate, LocalDate endDate) {
		if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private String uniqueInviteCode() {
		for (int attempt = 0; attempt < INVITE_CODE_ATTEMPTS; attempt++) {
			String candidate = inviteCodeGenerator.generate();
			if (!classroomRepository.existsByInviteCode(candidate)) {
				return candidate;
			}
		}
		throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
	}

	private InviteCodeResponse inviteCodeResponse(Classroom classroom) {
		return new InviteCodeResponse(classroom.getId(), classroom.getInviteCode());
	}

	private String normalizeInviteCode(String inviteCode) {
		String normalized = inviteCode == null
			? ""
			: inviteCode.trim().toUpperCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_INVITE_CODE);
		}
		return normalized;
	}

	private String normalizedQuery(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		return query.trim();
	}

	private String normalizedRequired(String value, int maxLength) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty() || normalized.length() > maxLength) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}

	private String normalizedOptional(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		if (normalized.length() > maxLength) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}

	private Sort classroomSort(ClassroomSort sort) {
		if (sort == ClassroomSort.NAME) {
			return Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"));
		}
		return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
	}

	private User activeUser(Long userId) {
		return userRepository.findById(userId)
			.filter(User::isActive)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	private void assertActive(Classroom classroom) {
		if (classroom.getStatus() == ClassroomStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.CLASSROOM_COMPLETED);
		}
	}

	private void requireInstructor(UserRole role) {
		if (role != UserRole.INSTRUCTOR) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
	}

	private void requireClassroomRole(UserRole role) {
		if (role != UserRole.LEARNER && role != UserRole.INSTRUCTOR) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
	}

	private record ClassroomLearnerMetrics(
		Integer progressRate,
		ClassroomLastStudiedResponse lastStudied
	) {
	}
}
