package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.classroom.ClassroomWeekMaterial;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.classroom.ClassroomWeekStatus;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:material-overview;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/material-overview"
	}
)
@ActiveProfiles("jpa-context")
@Transactional
class MaterialOverviewJpaTest {

	@Autowired private UserRepository userRepository;
	@Autowired private LearningMaterialRepository materialRepository;
	@Autowired private MaterialOverviewRepository overviewRepository;
	@Autowired private MaterialOverviewService overviewService;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ClassroomWeekRepository weekRepository;
	@Autowired private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Autowired private EntityManager entityManager;
	@MockitoBean private MaterialExtractionRecoveryScheduler recoveryScheduler;

	@Test
	void missingRowReturnsPendingForOwnerAndClassroomMemberAndHidesOutsider() {
		Fixture fixture = fixture();

		assertThat(overviewService.get(
			fixture.owner().getId(),
			fixture.material().getId()
		)).satisfies(response -> {
			assertThat(response.materialId()).isEqualTo(fixture.material().getId());
			assertThat(response.content()).isNull();
			assertThat(response.status()).isEqualTo("PENDING");
			assertThat(response.updatedAt()).isNull();
		});
		assertThat(overviewService.get(
			fixture.member().getId(),
			fixture.material().getId()
		).status()).isEqualTo("PENDING");
		assertThatThrownBy(() -> overviewService.get(
			fixture.outsider().getId(),
			fixture.material().getId()
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
		);
	}

	@Test
	void readyRowReturnsStoredContentAndUpdatedAt() {
		Fixture fixture = fixture();
		MaterialOverview overview = MaterialOverview.createPending(fixture.material());
		overview.markReady("자료의 핵심 개요");
		overviewRepository.saveAndFlush(overview);
		entityManager.clear();

		var response = overviewService.get(
			fixture.member().getId(),
			fixture.material().getId()
		);

		assertThat(response.materialId()).isEqualTo(fixture.material().getId());
		assertThat(response.content()).isEqualTo("자료의 핵심 개요");
		assertThat(response.status()).isEqualTo("READY");
		assertThat(response.updatedAt()).isNotNull();
	}

	@Test
	void failedRowReturnsNullContent() {
		Fixture fixture = fixture();
		MaterialOverview overview = MaterialOverview.createPending(fixture.material());
		overview.markReady("반환되면 안 되는 개요");
		overview.markFailed();
		overviewRepository.saveAndFlush(overview);
		entityManager.clear();

		var response = overviewService.get(
			fixture.owner().getId(),
			fixture.material().getId()
		);

		assertThat(response.status()).isEqualTo("FAILED");
		assertThat(response.content()).isNull();
		assertThat(response.updatedAt()).isNotNull();
	}

	private Fixture fixture() {
		User owner = userRepository.saveAndFlush(User.create(
			"overview-owner-" + System.nanoTime() + "@example.com",
			"hash",
			"owner",
			UserRole.INSTRUCTOR
		));
		User member = userRepository.saveAndFlush(User.create(
			"overview-member-" + System.nanoTime() + "@example.com",
			"hash",
			"member",
			UserRole.LEARNER
		));
		User outsider = userRepository.saveAndFlush(User.create(
			"overview-outsider-" + System.nanoTime() + "@example.com",
			"hash",
			"outsider",
			UserRole.LEARNER
		));
		Classroom classroom = classroomRepository.saveAndFlush(Classroom.create(
			owner,
			"Overview classroom",
			LocalDate.of(2026, 8, 1),
			LocalDate.of(2026, 12, 1),
			ClassroomColor.BLUE,
			null,
			"OVRV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
		));
		memberRepository.saveAndFlush(ClassroomMember.create(
			classroom,
			member,
			Instant.parse("2026-08-15T00:00:00Z")
		));
		ClassroomWeek week = weekRepository.saveAndFlush(ClassroomWeek.create(
			classroom,
			1,
			"Week 1",
			null,
			ClassroomWeekStatus.PUBLISHED,
			1
		));
		LearningMaterial material = LearningMaterial.create(
			owner,
			"Overview material",
			"materials/overview-" + System.nanoTime() + ".pdf"
		);
		material.markReady(3);
		material = materialRepository.saveAndFlush(material);
		weekMaterialRepository.saveAndFlush(ClassroomWeekMaterial.create(
			week,
			material,
			Instant.parse("2026-08-15T00:00:00Z")
		));
		return new Fixture(owner, member, outsider, material);
	}

	private record Fixture(
		User owner,
		User member,
		User outsider,
		LearningMaterial material
	) {
	}
}
