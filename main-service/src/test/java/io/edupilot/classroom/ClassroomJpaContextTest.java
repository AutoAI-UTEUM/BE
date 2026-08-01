package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import io.edupilot.classroom.dto.CreateClassroomRequest;
import io.edupilot.classroom.dto.CreateJoinRequest;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:classroom-jpa;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/classroom-jpa"
	}
)
@ActiveProfiles("jpa-context")
class ClassroomJpaContextTest {

	@Autowired
	private ClassroomRepository classroomRepository;
	@Autowired
	private ClassroomMemberRepository memberRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ClassroomService classroomService;

	@Test
	void persistsJoinApprovalAndQueriesOwnedAndMemberScopes() {
		assertThat(classroomRepository).isNotNull();
		User instructor = userRepository.save(User.create(
			"instructor@example.com", "hash", "홍강사", UserRole.INSTRUCTOR
		));
		User learner = userRepository.save(User.create(
			"learner@example.com", "hash", "김학습", UserRole.LEARNER
		));
		var classroom = classroomService.create(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			new CreateClassroomRequest(
				"AI 기초",
				LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 12, 15),
				ClassroomColor.BLUE,
				null
			)
		);
		var request = classroomService.requestJoin(
			learner.getId(),
			UserRole.LEARNER,
			new CreateJoinRequest(" " + classroom.inviteCode().toLowerCase() + " ")
		);

		classroomService.approve(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			request.requestId()
		);

		assertThat(memberRepository.existsByClassroom_IdAndUser_Id(
			classroom.classroomId(),
			learner.getId()
		)).isTrue();
		assertThat(classroomService.list(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			null,
			"AI",
			ClassroomSort.RECENT,
			0,
			20
		).items()).hasSize(1);
		assertThat(classroomService.list(
			learner.getId(),
			UserRole.LEARNER,
			null,
			"AI",
			ClassroomSort.NAME,
			0,
			20
		).items()).singleElement()
			.satisfies(item -> {
				assertThat(item.progressRate()).isZero();
				assertThat(item.pendingRequestCount()).isNull();
			});
	}
}
