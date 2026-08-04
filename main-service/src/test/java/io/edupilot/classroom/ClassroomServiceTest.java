package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.dto.CreateClassroomRequest;
import io.edupilot.classroom.dto.CreateJoinRequest;
import io.edupilot.classroom.dto.UpdateClassroomRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ClassroomServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-16T03:00:00Z");

	@Mock
	private ClassroomRepository classroomRepository;
	@Mock
	private ClassroomMemberRepository memberRepository;
	@Mock
	private ClassroomJoinRequestRepository joinRequestRepository;
	@Mock
	private ClassroomWeekRepository weekRepository;
	@Mock
	private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Mock
	private LearningProgressService progressService;
	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private ClassroomInviteCodeGenerator inviteCodeGenerator;

	private ClassroomService service;
	private User instructor;
	private User learner;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		service = new ClassroomService(
			classroomRepository,
			memberRepository,
			joinRequestRepository,
			weekRepository,
			weekMaterialRepository,
			progressService,
			sessionRepository,
			userRepository,
			inviteCodeGenerator,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		instructor = user(1L, "teacher@example.com", "홍강사", UserRole.INSTRUCTOR);
		learner = user(2L, "learner@example.com", "김학습", UserRole.LEARNER);
		classroom = classroom(30L, instructor, "7KMX-9QTR");
	}

	@Test
	void instructorCreatesClassroomWithCalculatedWeekAndUniqueInviteCode() {
		when(userRepository.findById(1L)).thenReturn(Optional.of(instructor));
		when(inviteCodeGenerator.generate()).thenReturn("AAAA-BBBB", "7KMX-9QTR");
		when(classroomRepository.existsByInviteCode("AAAA-BBBB")).thenReturn(true);
		when(classroomRepository.existsByInviteCode("7KMX-9QTR")).thenReturn(false);
		when(classroomRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			Classroom saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 30L);
			return saved;
		});

		var response = service.create(
			1L,
			UserRole.INSTRUCTOR,
			new CreateClassroomRequest(
				" AI 기초 ",
				LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 12, 15),
				ClassroomColor.BLUE,
				" 화요일 "
			)
		);

		assertThat(response.classroomId()).isEqualTo(30L);
		assertThat(response.weekCount()).isEqualTo(16);
		assertThat(response.currentWeek()).isEqualTo(3);
		assertThat(response.inviteCode()).isEqualTo("7KMX-9QTR");
		assertThat(response.name()).isEqualTo("AI 기초");
	}

	@Test
	void createRejectsNonInstructorColorOmissionAndReverseDates() {
		CreateClassroomRequest valid = new CreateClassroomRequest(
			"AI 기초",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 9, 7),
			ClassroomColor.BLUE,
			null
		);
		assertError(
			() -> service.create(2L, UserRole.LEARNER, valid),
			ErrorCode.ACCESS_DENIED
		);
		assertError(
			() -> service.create(
				1L,
				UserRole.INSTRUCTOR,
				new CreateClassroomRequest(
					"AI 기초",
					valid.startDate(),
					valid.endDate(),
					null,
					null
				)
			),
			ErrorCode.VALIDATION_FAILED
		);
		assertError(
			() -> service.create(
				1L,
				UserRole.INSTRUCTOR,
				new CreateClassroomRequest(
					"AI 기초",
					LocalDate.of(2026, 9, 2),
					LocalDate.of(2026, 9, 1),
					ClassroomColor.BLUE,
					null
				)
			),
			ErrorCode.VALIDATION_FAILED
		);
	}

	@Test
	void listUsesOwnedAndMemberUnionAndRoleSpecificFields() {
		Classroom other = classroom(31L, user(
			3L,
			"other@example.com",
			"타강사",
			UserRole.INSTRUCTOR
		), "ABCD-EFGH");
		when(classroomRepository.findVisibleByUserId(
			eq(1L), eq(null), eq("AI"), any(Pageable.class)
		)).thenReturn(new PageImpl<>(List.of(classroom, other)));
		when(memberRepository.countByClassroom_Id(any())).thenReturn(2L);
		var materialCount = org.mockito.Mockito.mock(
			ClassroomWeekMaterialRepository.ClassroomMaterialCount.class
		);
		when(materialCount.getClassroomId()).thenReturn(30L);
		when(materialCount.getMaterialCount()).thenReturn(3L);
		when(weekMaterialRepository.countDistinctMaterialsByClassroomIds(
			List.of(30L, 31L)
		)).thenReturn(List.of(materialCount));
		when(joinRequestRepository.countByClassroom_IdAndStatus(
			30L,
			ClassroomJoinRequestStatus.PENDING
		)).thenReturn(4L);

		var response = service.list(
			1L,
			UserRole.INSTRUCTOR,
			null,
			" AI ",
			ClassroomSort.RECENT,
			0,
			20
		);

		assertThat(response.items()).hasSize(2);
		assertThat(response.items().get(0).pendingRequestCount()).isEqualTo(4L);
		assertThat(response.items().get(0).materialCount()).isEqualTo(3L);
		assertThat(response.items().get(1).materialCount()).isZero();
		assertThat(response.items().get(0).progressRate()).isNull();
		assertThat(response.items().get(1).pendingRequestCount()).isNull();
		assertThat(response.items().get(1).progressRate()).isZero();
	}

	@Test
	void patchDistinguishesOmittedDescriptionFromExplicitNull() {
		when(classroomRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(classroom));
		UpdateClassroomRequest request = new UpdateClassroomRequest();
		request.setDescription(null);

		var response = service.update(1L, UserRole.INSTRUCTOR, 30L, request);

		assertThat(response.description()).isNull();
		UpdateClassroomRequest empty = new UpdateClassroomRequest();
		assertError(
			() -> service.update(1L, UserRole.INSTRUCTOR, 30L, empty),
			ErrorCode.VALIDATION_FAILED
		);
	}

	@Test
	void endDateCannotShrinkBelowExistingMaximumWeek() {
		when(classroomRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(classroom));
		when(weekRepository.findMaximumWeekNumber(30L)).thenReturn(3);
		UpdateClassroomRequest request = new UpdateClassroomRequest();
		request.setEndDate(LocalDate.of(2026, 9, 10));

		assertError(
			() -> service.update(1L, UserRole.INSTRUCTOR, 30L, request),
			ErrorCode.CLASSROOM_WEEK_RANGE_CONFLICT
		);
	}

	@Test
	void completeIsIdempotentAndCompletedClassroomRejectsWrites() {
		when(classroomRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(classroom));

		assertThat(service.complete(1L, UserRole.INSTRUCTOR, 30L).status())
			.isEqualTo(ClassroomStatus.COMPLETED);
		assertThat(service.complete(1L, UserRole.INSTRUCTOR, 30L).status())
			.isEqualTo(ClassroomStatus.COMPLETED);

		UpdateClassroomRequest update = new UpdateClassroomRequest();
		update.setName("수정");
		assertError(
			() -> service.update(1L, UserRole.INSTRUCTOR, 30L, update),
			ErrorCode.CLASSROOM_COMPLETED
		);
	}

	@Test
	void joinNormalizesCodeAndApprovalCreatesMemberInSameServiceTransaction() {
		when(classroomRepository.findByInviteCode("7KMX-9QTR"))
			.thenReturn(Optional.of(classroom));
		when(memberRepository.existsByClassroom_IdAndUser_Id(30L, 2L))
			.thenReturn(false);
		when(joinRequestRepository.findByClassroomAndUserForUpdate(30L, 2L))
			.thenReturn(Optional.empty());
		when(userRepository.findById(2L)).thenReturn(Optional.of(learner));
		when(joinRequestRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			ClassroomJoinRequest request = invocation.getArgument(0);
			ReflectionTestUtils.setField(request, "id", 50L);
			return request;
		});

		var pending = service.requestJoin(
			2L,
			UserRole.LEARNER,
			new CreateJoinRequest(" 7kmx-9qtr ")
		);
		assertThat(pending.status()).isEqualTo(ClassroomJoinRequestStatus.PENDING);

		ClassroomJoinRequest request = ClassroomJoinRequest.create(classroom, learner, NOW);
		ReflectionTestUtils.setField(request, "id", 50L);
		when(classroomRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(classroom));
		when(joinRequestRepository.findForUpdate(30L, 50L))
			.thenReturn(Optional.of(request));

		var approved = service.approve(1L, UserRole.INSTRUCTOR, 30L, 50L);

		assertThat(approved.status()).isEqualTo(ClassroomJoinRequestStatus.APPROVED);
		verify(memberRepository).save(any(ClassroomMember.class));
	}

	@Test
	void rejectedRequestReusesSameRowAndDuplicatePendingIsRejected() {
		ClassroomJoinRequest request = ClassroomJoinRequest.create(classroom, learner, NOW.minusSeconds(60));
		ReflectionTestUtils.setField(request, "id", 50L);
		request.reject(NOW.minusSeconds(30));
		when(classroomRepository.findByInviteCode("7KMX-9QTR"))
			.thenReturn(Optional.of(classroom));
		when(joinRequestRepository.findByClassroomAndUserForUpdate(30L, 2L))
			.thenReturn(Optional.of(request));

		var pending = service.requestJoin(
			2L,
			UserRole.LEARNER,
			new CreateJoinRequest("7KMX-9QTR")
		);
		assertThat(pending.requestId()).isEqualTo(50L);
		assertThat(pending.processedAt()).isNull();
		assertThat(pending.requestedAt()).isEqualTo(NOW);

		assertError(
			() -> service.requestJoin(
				2L,
				UserRole.LEARNER,
				new CreateJoinRequest("7KMX-9QTR")
			),
			ErrorCode.JOIN_REQUEST_ALREADY_PENDING
		);
	}

	@Test
	void invalidCompletedMemberAndPendingJoinRequestsReturnContractErrors() {
		when(classroomRepository.findByInviteCode("NONE-NONE"))
			.thenReturn(Optional.empty());
		assertError(
			() -> service.requestJoin(
				2L,
				UserRole.LEARNER,
				new CreateJoinRequest("NONE-NONE")
			),
			ErrorCode.INVALID_INVITE_CODE
		);

		classroom.complete();
		when(classroomRepository.findByInviteCode("7KMX-9QTR"))
			.thenReturn(Optional.of(classroom));
		assertError(
			() -> service.requestJoin(
				2L,
				UserRole.LEARNER,
				new CreateJoinRequest("7KMX-9QTR")
			),
			ErrorCode.INVALID_INVITE_CODE
		);
	}

	@Test
	void alreadyMemberAndAlreadyProcessedRequestReturnConflict() {
		when(classroomRepository.findByInviteCode("7KMX-9QTR"))
			.thenReturn(Optional.of(classroom));
		when(memberRepository.existsByClassroom_IdAndUser_Id(30L, 2L))
			.thenReturn(true);
		assertError(
			() -> service.requestJoin(
				2L,
				UserRole.LEARNER,
				new CreateJoinRequest("7KMX-9QTR")
			),
			ErrorCode.ALREADY_CLASSROOM_MEMBER
		);

		ClassroomJoinRequest processed = ClassroomJoinRequest.create(
			classroom,
			learner,
			NOW.minusSeconds(60)
		);
		ReflectionTestUtils.setField(processed, "id", 50L);
		processed.reject(NOW.minusSeconds(30));
		when(classroomRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(classroom));
		when(joinRequestRepository.findForUpdate(30L, 50L))
			.thenReturn(Optional.of(processed));
		assertError(
			() -> service.approve(1L, UserRole.INSTRUCTOR, 30L, 50L),
			ErrorCode.JOIN_REQUEST_ALREADY_PROCESSED
		);
	}

	@Test
	void regeneratedInviteCodeReplacesPreviousValue() {
		when(classroomRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(classroom));
		when(inviteCodeGenerator.generate()).thenReturn("WXYZ-9876");
		when(classroomRepository.existsByInviteCode("WXYZ-9876")).thenReturn(false);

		var response = service.regenerateInviteCode(
			1L,
			UserRole.INSTRUCTOR,
			30L
		);

		assertThat(response.inviteCode()).isEqualTo("WXYZ-9876");
		assertThat(classroom.getInviteCode()).isEqualTo("WXYZ-9876");
	}

	@Test
	void hidesOtherInstructorClassroomAndDeniesVisibleMemberManagement() {
		Classroom other = classroom(31L, instructor, "ABCD-EFGH");
		when(classroomRepository.findWithInstructorById(31L))
			.thenReturn(Optional.of(other));
		when(memberRepository.existsByClassroom_IdAndUser_Id(31L, 2L))
			.thenReturn(false, true);

		assertError(
			() -> service.detail(2L, UserRole.LEARNER, 31L),
			ErrorCode.CLASSROOM_NOT_FOUND
		);
		assertError(
			() -> service.inviteCode(2L, UserRole.LEARNER, 31L),
			ErrorCode.ACCESS_DENIED
		);
		verify(classroomRepository, never()).flush();
	}

	private Classroom classroom(Long id, User owner, String inviteCode) {
		Classroom value = Classroom.create(
			owner,
			"AI 기초",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			"설명",
			inviteCode
		);
		ReflectionTestUtils.setField(value, "id", id);
		return value;
	}

	private User user(Long id, String email, String name, UserRole role) {
		User value = User.create(email, "hash", name, role);
		ReflectionTestUtils.setField(value, "id", id);
		return value;
	}

	private void assertError(Runnable action, ErrorCode errorCode) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}
}
