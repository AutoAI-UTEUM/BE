package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.StudentLastActivity;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ClassroomStudentServiceTest {

	@Mock private ClassroomService classroomService;
	@Mock private ClassroomMemberRepository memberRepository;
	@Mock private LearningSessionRepository sessionRepository;

	private ClassroomStudentService service;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		service = new ClassroomStudentService(
			classroomService, memberRepository, sessionRepository
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
		when(memberRepository.findByClassroom_Id(eq(30L), any()))
			.thenReturn(new PageImpl<>(List.of(first, second)));
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
		verify(sessionRepository).findLastActivityByClassroomAndStudentIds(
			30L, List.of(40L, 41L)
		);
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

		verify(classroomService).requireOwnerForUpdate(
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
}
