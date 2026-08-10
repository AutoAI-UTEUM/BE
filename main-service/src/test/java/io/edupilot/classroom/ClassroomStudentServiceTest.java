package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.material.LearningMaterial;
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.QaMessageRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.session.StudentLastActivity;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ClassroomStudentServiceTest {
	private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");

	@Mock private ClassroomService classroomService;
	@Mock private ClassroomMemberRepository memberRepository;
	@Mock private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Mock private LearningSessionRepository sessionRepository;
	@Mock private QaMessageRepository qaMessageRepository;
	@Mock private LearningProgressService progressService;

	private ClassroomStudentService service;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		service = new ClassroomStudentService(
			classroomService,
			memberRepository,
			weekMaterialRepository,
			sessionRepository,
			qaMessageRepository,
			progressService,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		User instructor = user(
			1L, "teacher@example.com", "강사", null, UserRole.INSTRUCTOR
		);
		classroom = Classroom.create(
			instructor,
			"강의실",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 1),
			ClassroomColor.BLUE,
			null,
			"INVITE"
		);
		ReflectionTestUtils.setField(classroom, "id", 30L);
	}

	@Test
	void listIncludesProfileMembershipAndBatchLastActivityWithNullFallback() {
		Instant joinedAt = Instant.parse("2026-08-01T00:00:00Z");
		Instant lastActiveAt = Instant.parse("2026-08-03T00:00:00Z");
		ClassroomMember first = member(
			10L,
			user(40L, "one@example.com", "학생1", "컴퓨터공학", UserRole.LEARNER),
			joinedAt
		);
		ClassroomMember second = member(
			11L,
			user(41L, "two@example.com", "학생2", null, UserRole.LEARNER),
			joinedAt
		);
		when(memberRepository.findByClassroom_Id(eq(30L), any(Sort.class)))
			.thenReturn(List.of(first, second));
		when(sessionRepository.findLastActivityByClassroomAndStudentIds(
			30L, List.of(40L, 41L)
		)).thenReturn(List.of(new StudentLastActivity(40L, lastActiveAt)));

		var response = service.list(
			1L, UserRole.INSTRUCTOR, 30L, 0, 20
		);

		assertThat(response.items()).hasSize(2);
		assertThat(response.items().get(0).studentId()).isEqualTo(40L);
		assertThat(response.items().get(0).name()).isEqualTo("학생1");
		assertThat(response.items().get(0).email()).isEqualTo("one@example.com");
		assertThat(response.items().get(0).affiliation()).isEqualTo("컴퓨터공학");
		assertThat(response.items().get(0).joinedAt()).isEqualTo(joinedAt);
		assertThat(response.items().get(0).status()).isEqualTo("ACTIVE");
		assertThat(response.items().get(0).lastActiveAt()).isEqualTo(lastActiveAt);
		assertThat(response.items().get(1).affiliation()).isNull();
		assertThat(response.items().get(1).lastActiveAt()).isNull();
		assertThat(response.items()).allSatisfy(item -> {
			assertThat(item.averageProgressRate()).isZero();
			assertThat(item.aiQuestionCountLast7Days()).isZero();
		});
		verify(sessionRepository).findLastActivityByClassroomAndStudentIds(
			30L, List.of(40L, 41L)
		);
	}

	@Test
	void loadsThreeStudentMetricsWithOneBatchPerSourceAndZeroFallback() {
		List<ClassroomMember> members = members();
		LearningMaterial material = mock(LearningMaterial.class);
		when(material.getId()).thenReturn(50L);
		when(memberRepository.findByClassroom_Id(eq(30L), any(Sort.class)))
			.thenReturn(members);
		when(weekMaterialRepository.findVisibleReportMaterials(
			eq(30L), eq(null), eq(NOW), any(), any()
		)).thenReturn(List.of(material));
		when(progressService.calculateStudentProgressRates(
			30L, List.of(material), List.of(40L, 41L, 42L)
		)).thenReturn(Map.of(40L, 60, 41L, 10, 42L, 0));
		when(sessionRepository.findLastActivityByClassroomAndStudentIds(
			30L, List.of(40L, 41L, 42L)
		)).thenReturn(List.of(
			new StudentLastActivity(40L, NOW.minus(Duration.ofDays(1))),
			new StudentLastActivity(41L, NOW.minus(Duration.ofDays(2)))
		));
		var firstCount = questionCount(40L, 2L);
		var secondCount = questionCount(41L, 1L);
		when(qaMessageRepository.findRecentQuestionCountsByStudentIds(
			eq(30L),
			eq(List.of(40L, 41L, 42L)),
			eq(List.of(50L)),
			eq(List.of(SessionStatus.ACTIVE, SessionStatus.COMPLETED)),
			eq(NOW.minus(Duration.ofDays(7)))
		)).thenReturn(List.of(firstCount, secondCount));

		var response = service.list(
			1L, UserRole.INSTRUCTOR, 30L, 0, 20, null, null
		);

		assertThat(response.items()).extracting(
			item -> item.averageProgressRate(),
			item -> item.aiQuestionCountLast7Days()
		).containsExactly(
			org.assertj.core.groups.Tuple.tuple(60, 2L),
			org.assertj.core.groups.Tuple.tuple(10, 1L),
			org.assertj.core.groups.Tuple.tuple(0, 0L)
		);
		verify(progressService).calculateStudentProgressRates(
			30L, List.of(material), List.of(40L, 41L, 42L)
		);
		verify(sessionRepository).findLastActivityByClassroomAndStudentIds(
			30L, List.of(40L, 41L, 42L)
		);
		verify(qaMessageRepository).findRecentQuestionCountsByStudentIds(
			eq(30L), any(), any(), any(), eq(NOW.minus(Duration.ofDays(7)))
		);
	}

	@Test
	void filtersByTrimmedPartialNameAndSupportsAllSorts() {
		List<ClassroomMember> members = members();
		when(memberRepository.findByClassroom_Id(eq(30L), any(Sort.class)))
			.thenReturn(members);
		when(progressService.calculateStudentProgressRates(
			eq(30L), eq(List.of()), any()
		)).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			List<Long> ids = invocation.getArgument(2);
			return ids.stream().collect(java.util.stream.Collectors.toMap(
				id -> id,
				id -> Map.of(40L, 60, 41L, 10, 42L, 30).get(id)
			));
		});
		when(sessionRepository.findLastActivityByClassroomAndStudentIds(
			eq(30L), any()
		)).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			List<Long> ids = invocation.getArgument(1);
			return ids.stream()
				.filter(id -> id != 42L)
				.map(id -> new StudentLastActivity(
					id,
					id == 40L ? NOW.minusSeconds(60) : NOW.minusSeconds(120)
				))
				.toList();
		});

		assertThat(service.list(
			1L, UserRole.INSTRUCTOR, 30L, 0, 20, " li ", null
		).items()).extracting(item -> item.name())
			.containsExactly("Charlie", "Alice");
		assertThat(service.list(
			1L, UserRole.INSTRUCTOR, 30L, 0, 20, "missing", null
		).items()).isEmpty();
		assertThat(service.list(
			1L, UserRole.INSTRUCTOR, 30L, 0, 20, null, ClassroomStudentSort.NAME
		).items()).extracting(item -> item.name())
			.containsExactly("Alice", "Bob", "Charlie");
		assertThat(service.list(
			1L, UserRole.INSTRUCTOR, 30L, 0, 20, null, ClassroomStudentSort.LOW_PROGRESS
		).items()).extracting(item -> item.studentId())
			.containsExactly(41L, 42L, 40L);
		assertThat(service.list(
			1L, UserRole.INSTRUCTOR, 30L, 0, 20, null, ClassroomStudentSort.RECENT_ACTIVITY
		).items()).extracting(item -> item.studentId())
			.containsExactly(40L, 41L, 42L);
	}

	@Test
	void removeDeletesOnlyMembership() {
		ClassroomMember member = member(
			10L,
			user(40L, "one@example.com", "학생", null, UserRole.LEARNER),
			Instant.EPOCH
		);
		when(memberRepository.findByClassroom_IdAndUser_Id(30L, 40L))
			.thenReturn(java.util.Optional.of(member));

		service.remove(1L, UserRole.INSTRUCTOR, 30L, 40L);

		verify(classroomService).requireStrictOwnerForUpdate(
			1L, UserRole.INSTRUCTOR, 30L
		);
		verify(memberRepository).delete(member);
	}

	private User user(
		Long id,
		String email,
		String name,
		String affiliation,
		UserRole role
	) {
		User user = User.create(
			email, "hash", name, role, affiliation, false, null, null, null
		);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	private ClassroomMember member(Long id, User user, Instant joinedAt) {
		ClassroomMember member = ClassroomMember.create(classroom, user, joinedAt);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private List<ClassroomMember> members() {
		return List.of(
			member(10L, user(40L, "charlie@example.com", "Charlie", null, UserRole.LEARNER), NOW),
			member(11L, user(41L, "alice@example.com", "Alice", null, UserRole.LEARNER), NOW.minusSeconds(1)),
			member(12L, user(42L, "bob@example.com", "Bob", null, UserRole.LEARNER), NOW.minusSeconds(2))
		);
	}

	private QaMessageRepository.StudentQuestionCount questionCount(
		Long studentId,
		long count
	) {
		var result = mock(QaMessageRepository.StudentQuestionCount.class);
		when(result.getStudentId()).thenReturn(studentId);
		when(result.getQuestionCount()).thenReturn(count);
		return result;
	}
}
