package io.edupilot.classroom;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.dto.ClassroomDetailResponse;
import io.edupilot.classroom.dto.ClassroomJoinRequestListResponse;
import io.edupilot.classroom.dto.ClassroomListResponse;
import io.edupilot.classroom.dto.ClassroomSummaryResponse;
import io.edupilot.classroom.dto.CreateClassroomRequest;
import io.edupilot.classroom.dto.CreateJoinRequest;
import io.edupilot.classroom.dto.InviteCodeResponse;
import io.edupilot.classroom.dto.JoinRequestListResponse;
import io.edupilot.classroom.dto.JoinRequestProcessResponse;
import io.edupilot.classroom.dto.JoinRequestResponse;
import io.edupilot.classroom.dto.UpdateClassroomRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
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
	private final UserRepository userRepository;
	private final ClassroomInviteCodeGenerator inviteCodeGenerator;
	private final Clock clock;

	public ClassroomService(
		ClassroomRepository classroomRepository,
		ClassroomMemberRepository memberRepository,
		ClassroomJoinRequestRepository joinRequestRepository,
		UserRepository userRepository,
		ClassroomInviteCodeGenerator inviteCodeGenerator,
		Clock clock
	) {
		this.classroomRepository = classroomRepository;
		this.memberRepository = memberRepository;
		this.joinRequestRepository = joinRequestRepository;
		this.userRepository = userRepository;
		this.inviteCodeGenerator = inviteCodeGenerator;
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
		return detailResponse(classroom, true);
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
		Page<ClassroomSummaryResponse> responses = classrooms.map(classroom -> {
			boolean ownerView = classroom.getInstructorId().equals(userId);
			return ClassroomSummaryResponse.from(
				classroom,
				ownerView,
				currentWeek(classroom),
				memberRepository.countByClassroom_Id(classroom.getId()),
				ownerView ? pendingCount(classroom.getId()) : 0
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
		classroom.update(
			request.isNamePresent() ? normalizedRequired(request.getName(), 100) : null,
			request.isEndDatePresent() ? request.getEndDate() : null,
			request.isColorPresent() ? request.getColor() : null,
			request.isDescriptionPresent(),
			request.isDescriptionPresent()
				? normalizedOptional(request.getDescription(), 255)
				: null
		);
		classroomRepository.flush();
		return detailResponse(classroom, true);
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
		return detailResponse(classroom, true);
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
			return JoinRequestResponse.from(joinRequest);
		}

		User user = activeUser(userId);
		ClassroomJoinRequest created = joinRequestRepository.saveAndFlush(
			ClassroomJoinRequest.create(classroom, user, clock.instant())
		);
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
		Classroom classroom,
		boolean ownerView
	) {
		return ClassroomDetailResponse.from(
			classroom,
			ownerView,
			currentWeek(classroom),
			memberRepository.countByClassroom_Id(classroom.getId()),
			ownerView ? pendingCount(classroom.getId()) : 0
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
			|| request.isEndDatePresent() && request.getEndDate() == null
			|| request.isColorPresent() && request.getColor() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (request.isEndDatePresent()) {
			validateDates(classroom.getStartDate(), request.getEndDate());
		}
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
}
