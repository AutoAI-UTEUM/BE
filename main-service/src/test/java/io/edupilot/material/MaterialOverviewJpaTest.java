package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.INSTANT;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import io.edupilot.ai.dto.OutlineResponse;
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

	private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

	@Autowired private UserRepository userRepository;
	@Autowired private LearningMaterialRepository materialRepository;
	@Autowired private MaterialOverviewRepository overviewRepository;
	@Autowired private MaterialPageRepository pageRepository;
	@Autowired private MaterialOverviewService overviewService;
	@Autowired private MaterialOutlinePersistenceService outlinePersistenceService;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ClassroomWeekRepository weekRepository;
	@Autowired private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Autowired private EntityManager entityManager;
	@MockitoBean private MaterialExtractionRecoveryScheduler recoveryScheduler;
	@MockitoBean private OutlineBackfillScheduler outlineBackfillScheduler;
	@MockitoBean private CaptionBackfillScheduler captionBackfillScheduler;
	@MockitoBean private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
	}

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
		overview.markReady("자료의 핵심 개요", outline());
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
		MaterialOverview stored = overviewRepository.findByMaterial_Id(
			fixture.material().getId()
		).orElseThrow();
		assertThat(stored.getOutline().materialSummary())
			.isEqualTo("자료의 핵심 요약입니다.");
		assertThat(stored.getOutline().sections()).hasSize(1);
	}

	@Test
	void failedRowReturnsNullContent() {
		Fixture fixture = fixture();
		MaterialOverview overview = MaterialOverview.createPending(fixture.material());
		overview.markReady("반환되면 안 되는 개요", outline());
		overview.markFailed(NOW);
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

	@Test
	void outlineFailureLeavesLearningMaterialReady() {
		Fixture fixture = fixture();

		assertThat(outlinePersistenceService.markFailed(fixture.material().getId()))
			.isTrue();
		entityManager.flush();
		entityManager.clear();

		LearningMaterial material = materialRepository.findById(
			fixture.material().getId()
		).orElseThrow();
		assertThat(material.getProcessingStatus())
			.isEqualTo(MaterialProcessingStatus.READY);
		assertThat(material.getStatus()).isEqualTo(MaterialStatus.ACTIVE);
		assertThat(overviewRepository.findByMaterial_Id(material.getId()))
			.get()
			.extracting(MaterialOverview::getStatus)
			.isEqualTo(MaterialOverviewStatus.FAILED);
	}

	@Test
	void backfillCandidatesIncludeOnlyActiveReadyMaterialsWithoutOverview() {
		Fixture missing = fixture();
		Fixture failed = fixture();
		MaterialOverview failedOverview = MaterialOverview.createPending(
			failed.material()
		);
		failedOverview.markFailed(NOW);
		overviewRepository.saveAndFlush(failedOverview);

		LearningMaterial processing = materialRepository.saveAndFlush(
			LearningMaterial.create(
				missing.owner(),
				"Processing material",
				"materials/processing-" + System.nanoTime() + ".pdf"
			)
		);
		LearningMaterial deleted = LearningMaterial.create(
			missing.owner(),
			"Deleted material",
			"materials/deleted-" + System.nanoTime() + ".pdf"
		);
		deleted.markReady(1);
		deleted.delete();
		deleted = materialRepository.saveAndFlush(deleted);

		List<Long> candidateIds = materialRepository.findMissingOverviewIds(
			PageRequest.of(0, 10)
		);

		assertThat(candidateIds).contains(missing.material().getId());
		assertThat(candidateIds).doesNotContain(
			failed.material().getId(),
			processing.getId(),
			deleted.getId()
		);
	}

	@Test
	void backfillCandidatesPreferMissingThenIncludeOnlyFailedAfterBackoff() {
		Fixture missing = fixture();
		Fixture expired = fixture();
		Fixture recent = fixture();
		MaterialOverview expiredOverview = MaterialOverview.createPending(
			expired.material()
		);
		expiredOverview.markFailed(NOW.minus(Duration.ofHours(24)));
		overviewRepository.saveAndFlush(expiredOverview);
		MaterialOverview recentOverview = MaterialOverview.createPending(
			recent.material()
		);
		recentOverview.markFailed(
			NOW.minus(Duration.ofHours(24)).plusSeconds(1)
		);
		overviewRepository.saveAndFlush(recentOverview);
		setOverviewUpdatedAt(
			expired.material().getId(),
			NOW.minus(Duration.ofHours(24))
		);
		setOverviewUpdatedAt(
			recent.material().getId(),
			NOW.minus(Duration.ofHours(24)).plusSeconds(1)
		);
		entityManager.clear();

		assertThat(outlinePersistenceService.findBackfillCandidates(2))
			.containsExactly(
				missing.material().getId(),
				expired.material().getId()
			);
	}

	@Test
	void snapshotAllowsFailedOverviewAndRetryCanBecomeReady() {
		Fixture fixture = fixture();
		fixture.material().replaceXaiFileId("file-outline-snapshot");
		materialRepository.flush();
		pageRepository.saveAndFlush(MaterialPage.create(
			fixture.material(),
			1,
			"retry page text"
		));
		MaterialOverview overview = MaterialOverview.createPending(
			fixture.material()
		);
		overview.markFailed(NOW.minus(Duration.ofHours(24)));
		overviewRepository.saveAndFlush(overview);
		entityManager.clear();

		assertThat(outlinePersistenceService.snapshot(fixture.material().getId()))
			.get()
			.satisfies(snapshot -> {
				assertThat(snapshot.xaiFileId())
					.isEqualTo("file-outline-snapshot");
				assertThat(snapshot.pages())
					.singleElement()
					.satisfies(page -> {
						assertThat(page.pageNumber()).isEqualTo(1);
						assertThat(page.text()).isEqualTo("retry page text");
					});
			});

		assertThat(outlinePersistenceService.markReady(
			fixture.material().getId(),
			"retry overview",
			outline()
		)).isTrue();
		entityManager.flush();
		entityManager.clear();

		assertThat(overviewRepository.findByMaterial_Id(fixture.material().getId()))
			.get()
			.satisfies(stored -> {
				assertThat(stored.getStatus())
					.isEqualTo(MaterialOverviewStatus.READY);
				assertThat(stored.getContent()).isEqualTo("retry overview");
			});
	}

	@Test
	void repeatedFailureRefreshesBackoffTimestamp() {
		Fixture fixture = fixture();
		Instant previousFailure = NOW.minus(Duration.ofDays(2));
		MaterialOverview overview = MaterialOverview.createPending(
			fixture.material()
		);
		overview.markFailed(previousFailure);
		overviewRepository.saveAndFlush(overview);
		setOverviewUpdatedAt(fixture.material().getId(), previousFailure);
		entityManager.clear();

		assertThat(outlinePersistenceService.markFailed(fixture.material().getId()))
			.isTrue();
		entityManager.flush();
		entityManager.clear();
		assertThat(overviewRepository.findByMaterial_Id(fixture.material().getId()))
			.get()
			.extracting(MaterialOverview::getUpdatedAt)
			.asInstanceOf(INSTANT)
			.isAfter(previousFailure);
		assertThat(outlinePersistenceService.findBackfillCandidates(10))
			.doesNotContain(fixture.material().getId());
	}

	@Test
	void captionRoundTripPreservesOriginalTextAndCompletionExcludesBackfill() {
		Fixture fixture = fixture();
		Fixture pendingCaption = fixture();
		MaterialPage page = pageRepository.saveAndFlush(MaterialPage.create(
			fixture.material(),
			1,
			"original text"
		));
		page.updateCaption("diagram caption");
		fixture.material().completeCaptionGeneration(
			Instant.parse("2026-08-20T00:00:00Z")
		);
		pageRepository.flush();
		materialRepository.flush();
		entityManager.clear();

		MaterialPage stored = pageRepository
			.findByMaterial_IdAndPageNumber(fixture.material().getId(), 1)
			.orElseThrow();
		assertThat(stored.getTextContent()).isEqualTo("original text");
		assertThat(stored.getCaption()).isEqualTo("diagram caption");
		assertThat(outlinePersistenceService.snapshot(fixture.material().getId()))
			.get()
			.satisfies(snapshot -> assertThat(snapshot.pages().getFirst().text())
				.isEqualTo("original text\n\n[그림 설명] diagram caption"));
		assertThat(materialRepository.findMissingCaptionIds(PageRequest.of(0, 10)))
			.contains(pendingCaption.material().getId())
			.doesNotContain(fixture.material().getId());
	}

	private void setOverviewUpdatedAt(Long materialId, Instant updatedAt) {
		entityManager.createNativeQuery("""
			update material_overviews
			set updated_at = :updatedAt
			where material_id = :materialId
			""")
			.setParameter("updatedAt", Timestamp.from(updatedAt))
			.setParameter("materialId", materialId)
			.executeUpdate();
	}

	private OutlineResponse outline() {
		return new OutlineResponse(
			"1.0",
			"자료의 핵심 요약입니다.",
			List.of(new OutlineResponse.Section(
				"첫 번째 단원",
				1,
				3,
				List.of("개념")
			)),
			3
		);
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
