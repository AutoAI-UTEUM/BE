package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.ExtractedPage;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import jakarta.persistence.EntityManager;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:material-failure;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/material-failure"
	}
)
@ActiveProfiles("jpa-context")
@Transactional
class MaterialFailureJpaTest {
	@DynamicPropertySource
	static void isolatedMysql(DynamicPropertyRegistry registry) {
		if (!"true".equals(System.getenv("RUNTIME_REGRESSIONS_MYSQL"))) { return; }
		registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:33316/runtime_material_synthetic");
		registry.add("spring.datasource.username", () -> "root");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}
	@Autowired private io.edupilot.deletion.DeletionJournalLockRepository deletionLocks;
	@BeforeEach
	void seedDeletionJournal() {
		if ("true".equals(System.getenv("RUNTIME_REGRESSIONS_MYSQL"))) {
			assertThat(jdbcTemplate.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbcTemplate.queryForObject("select database()", String.class)).isEqualTo("runtime_material_synthetic");
		}
		// create-drop omits V56's migration-created mutex; production still requires the migration.
		if(!deletionLocks.existsById(1)) { deletionLocks.saveAndFlush(io.edupilot.deletion.DeletionJournalLock.initial()); }
	}

	@Autowired private UserRepository userRepository;
	@Autowired private LearningMaterialRepository materialRepository;
	@Autowired private MaterialExtractionPersistenceService persistenceService;
	@Autowired private MaterialCaptionPersistenceService captionPersistenceService;
	@Autowired private MaterialPageRepository pageRepository;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManager entityManager;
	@MockitoBean private MaterialExtractionRecoveryScheduler recoveryScheduler;

	@ParameterizedTest
	@EnumSource(CaptionFailureReason.class)
	void permanentCaptionFailureStaysReadyAndIsExcludedFromBackfill(CaptionFailureReason reason) {
		LearningMaterial material = material(owner(), "caption-rejection");
		material.markReady(1);
		pageRepository.saveAndFlush(MaterialPage.create(material, 1, "original text"));
		Instant completedAt = Instant.parse("2026-09-30T00:00:00Z");

		assertThat(captionPersistenceService.markPermanentlyFailed(material.getId(), reason, completedAt))
			.isTrue();
		entityManager.flush();
		entityManager.clear();

		LearningMaterial stored = materialRepository.findById(material.getId()).orElseThrow();
		assertThat(stored.isReady()).isTrue();
		assertThat(stored.isActive()).isTrue();
		assertThat(stored.getCaptionFailureReason()).isEqualTo(reason);
		assertThat(stored.getCaptionsCompletedAt()).isEqualTo(completedAt);
		assertThat(stored.getFailureReason()).isNull();
		assertThat(stored.getFailureTraceId()).isNull();
		assertThat(captionPersistenceService.snapshot(material.getId())).isEmpty();
		assertThat(captionPersistenceService.findBackfillCandidates(100)).doesNotContain(material.getId());
		assertThat(captionPersistenceService.markCompleted(material.getId(), completedAt.plusSeconds(1)))
			.isFalse();
		captionPersistenceService.applyCaptions(material.getId(), Map.of(1, "late caption"));
		entityManager.flush();
		entityManager.clear();
		MaterialPage storedPage = pageRepository.findByMaterial_IdOrderByPageNumberAsc(material.getId())
			.getFirst();
		assertThat(storedPage.getTextContent()).isEqualTo("original text");
		assertThat(storedPage.getCaption()).isNull();
	}

	@Test
	void captionReasonAlonePreventsAutomaticRetryAndPendingReadyMaterialRemainsEligible() {
		User owner = owner();
		LearningMaterial rejected = material(owner, "caption-guard");
		rejected.markReady(1);
		rejected.failCaptionGeneration(CaptionFailureReason.INVALID_PAGE_DIMENSIONS, Instant.now());
		LearningMaterial pending = material(owner, "caption-pending");
		pending.markReady(1);
		LearningMaterial deleted = material(owner, "caption-deleted");
		deleted.markReady(1);
		deleted.delete();
		LearningMaterial processing = material(owner, "caption-processing");
		LearningMaterial failed = material(owner, "caption-failed");
		failed.markFailed(MaterialFailureReason.EXTRACTION_FAILED, null);
		LearningMaterial finished = material(owner, "caption-finished");
		finished.markReady(1);
		finished.completeCaptionGeneration(Instant.now());
		entityManager.flush();
		jdbcTemplate.update("update learning_materials set captions_completed_at = null where id = ?",
			rejected.getId());
		entityManager.clear();

		assertThat(captionPersistenceService.snapshot(rejected.getId())).isEmpty();
		assertThat(captionPersistenceService.findBackfillCandidates(100))
			.contains(pending.getId())
			.doesNotContain(rejected.getId(), deleted.getId(), processing.getId(), failed.getId(), finished.getId());
		assertThat(materialRepository.findById(pending.getId()).orElseThrow().getCaptionFailureReason())
			.isNull();
	}

	@Test
	void persistsFailureMetadataWithFailedMaterial() {
		User owner = userRepository.saveAndFlush(io.edupilot.VerifiedTestUsers.legacyVerified(User.create(
			"material-failure@example.com",
			"hash",
			"owner"
		)));
		LearningMaterial material = materialRepository.saveAndFlush(
			LearningMaterial.create(
				owner,
				"material",
				"materials/failure.pdf"
			)
		);

		assertThat(persistenceService.fail(
			material.getId(),
			MaterialFailureReason.PAGE_LIMIT_EXCEEDED,
			"upload-trace-jpa"
		)).isTrue();
		entityManager.flush();
		entityManager.clear();

		LearningMaterial saved = materialRepository.findById(material.getId())
			.orElseThrow();
		assertThat(saved.getProcessingStatus())
			.isEqualTo(MaterialProcessingStatus.FAILED);
		assertThat(saved.getFailureReason())
			.isEqualTo(MaterialFailureReason.PAGE_LIMIT_EXCEEDED);
		assertThat(saved.getFailureTraceId()).isEqualTo("upload-trace-jpa");
	}

	@Test
	void extractionPersistsNonBlankXaiFileIdAndReturnsReplacedId() {
		LearningMaterial material = material(owner(), "xai-file");
		material.replaceXaiFileId("file-old");
		materialRepository.flush();

		var result = persistenceService.complete(
			material.getId(),
			List.of(new ExtractedPage(1, "page")),
			"  file-new  "
		);
		entityManager.flush();
		entityManager.clear();

		assertThat(result.applied()).isTrue();
		assertThat(result.replacedXaiFileId()).isEqualTo("file-old");
		LearningMaterial saved = materialRepository.findById(material.getId())
			.orElseThrow();
		assertThat(saved.getXaiFileId()).isEqualTo("file-new");
		assertThat(saved.getProcessingStatus())
			.isEqualTo(MaterialProcessingStatus.READY);
	}

	@Test
	void blankXaiFileIdDoesNotOverwriteStoredValue() {
		LearningMaterial material = material(owner(), "blank-xai-file");
		material.replaceXaiFileId("file-existing");
		materialRepository.flush();

		var result = persistenceService.complete(
			material.getId(),
			List.of(new ExtractedPage(1, "page")),
			"   "
		);
		entityManager.flush();
		entityManager.clear();

		assertThat(result.replacedXaiFileId()).isNull();
		assertThat(materialRepository.findById(material.getId()).orElseThrow()
			.getXaiFileId()).isEqualTo("file-existing");
	}

	@ParameterizedTest
	@EnumSource(
		value = MaterialFailureReason.class,
		names = {
			"UNSUPPORTED_FORMAT",
			"ENCRYPTED_PDF",
			"NO_TEXT_CONTENT",
			"FILE_TOO_LARGE"
		}
	)
	void persistsExpandedFailureReason(MaterialFailureReason failureReason) {
		LearningMaterial material = material(owner(), "expanded-failure");

		material.markFailed(failureReason, "expanded-trace");
		materialRepository.flush();
		entityManager.clear();

		LearningMaterial saved = materialRepository.findById(material.getId())
			.orElseThrow();
		assertThat(saved.getProcessingStatus())
			.isEqualTo(MaterialProcessingStatus.FAILED);
		assertThat(saved.getFailureReason()).isEqualTo(failureReason);
		assertThat(saved.getFailureTraceId()).isEqualTo("expanded-trace");
	}

	@Test
	void failsOnlyActiveProcessingMaterialsAtOrBeforeThreshold() {
		Instant now = Instant.parse("2026-08-13T03:00:00Z");
		Instant cutoff = now.minusSeconds(30 * 60L);
		User owner = owner();
		LearningMaterial expired = material(owner, "expired");
		LearningMaterial boundary = material(owner, "boundary");
		LearningMaterial recent = material(owner, "recent");
		LearningMaterial ready = material(owner, "ready");
		ready.markReady(1);
		LearningMaterial failed = material(owner, "failed");
		failed.markFailed(MaterialFailureReason.EXTRACTION_FAILED, "old-trace");
		LearningMaterial deleted = material(owner, "deleted");
		deleted.delete();
		materialRepository.flush();
		touch(expired.getId(), cutoff.minusSeconds(1));
		touch(boundary.getId(), cutoff);
		touch(recent.getId(), cutoff.plusSeconds(1));
		touch(ready.getId(), cutoff.minusSeconds(1));
		touch(failed.getId(), cutoff.minusSeconds(1));
		touch(deleted.getId(), cutoff.minusSeconds(1));
		entityManager.clear();

		assertThat(persistenceService.failStuckExtractions(cutoff, now, 100))
			.isEqualTo(2);
		entityManager.clear();

		assertMaterial(expired.getId(), MaterialStatus.ACTIVE,
			MaterialProcessingStatus.FAILED, MaterialFailureReason.EXTRACTION_FAILED);
		assertMaterial(boundary.getId(), MaterialStatus.ACTIVE,
			MaterialProcessingStatus.FAILED, MaterialFailureReason.EXTRACTION_FAILED);
		assertMaterial(recent.getId(), MaterialStatus.ACTIVE,
			MaterialProcessingStatus.PROCESSING, null);
		assertMaterial(ready.getId(), MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY, null);
		assertMaterial(failed.getId(), MaterialStatus.ACTIVE,
			MaterialProcessingStatus.FAILED, MaterialFailureReason.EXTRACTION_FAILED);
		assertMaterial(deleted.getId(), MaterialStatus.DELETED,
			MaterialProcessingStatus.PROCESSING, null);
		assertThat(materialRepository.findById(expired.getId()).orElseThrow()
			.getFailureTraceId()).isNull();
	}

	@Test
	void completedBetweenScanAndBulkUpdateIsNotOverwritten() {
		Instant now = Instant.parse("2026-08-13T03:00:00Z");
		Instant cutoff = now.minusSeconds(30 * 60L);
		LearningMaterial material = material(owner(), "race-ready");
		touch(material.getId(), cutoff.minusSeconds(1));
		entityManager.clear();
		List<Long> candidates = materialRepository.findStuckProcessingIds(
			cutoff,
			PageRequest.of(0, 100)
		);

		assertThat(persistenceService.complete(
			material.getId(),
			List.of(new ExtractedPage(1, "page"))
		)).isTrue();
		assertThat(materialRepository.failStuckProcessing(candidates, cutoff, now))
			.isZero();
		entityManager.clear();

		assertMaterial(material.getId(), MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY, null);
	}

	@Test
	void lateCompletionAfterRecoveryFailureIsDiscarded() {
		Instant now = Instant.parse("2026-08-13T03:00:00Z");
		Instant cutoff = now.minusSeconds(30 * 60L);
		LearningMaterial material = material(owner(), "late-complete");
		touch(material.getId(), cutoff);
		entityManager.clear();

		assertThat(persistenceService.failStuckExtractions(cutoff, now, 100))
			.isEqualTo(1);
		assertThat(persistenceService.complete(
			material.getId(),
			List.of(new ExtractedPage(1, "late page"))
		)).isFalse();
		entityManager.clear();

		assertMaterial(material.getId(), MaterialStatus.ACTIVE,
			MaterialProcessingStatus.FAILED, MaterialFailureReason.EXTRACTION_FAILED);
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from material_pages where material_id = ?",
			Integer.class,
			material.getId()
		)).isZero();
	}

	@Test
	void recoveryProcessesAtMostOneHundredMaterialsPerBatch() {
		Instant now = Instant.parse("2026-08-13T03:00:00Z");
		Instant cutoff = now.minusSeconds(30 * 60L);
		User owner = owner();
		List<LearningMaterial> materials = java.util.stream.IntStream
			.rangeClosed(1, 101)
			.mapToObj(index -> LearningMaterial.create(
				owner,
				"batch-" + index,
				"materials/batch-" + index + "-" + UUID.randomUUID() + ".pdf"
			))
			.toList();
		materialRepository.saveAllAndFlush(materials);
		jdbcTemplate.update(
			"update learning_materials set updated_at = ? where owner_id = ?",
			Timestamp.from(cutoff),
			owner.getId()
		);
		entityManager.clear();

		assertThat(persistenceService.failStuckExtractions(cutoff, now, 100))
			.isEqualTo(100);
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from learning_materials "
				+ "where owner_id = ? and processing_status = 'FAILED'",
			Integer.class,
			owner.getId()
		)).isEqualTo(100);
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from learning_materials "
				+ "where owner_id = ? and processing_status = 'PROCESSING'",
			Integer.class,
			owner.getId()
		)).isEqualTo(1);
	}

	private User owner() {
		return userRepository.saveAndFlush(io.edupilot.VerifiedTestUsers.legacyVerified(User.create(
			"material-recovery-" + UUID.randomUUID() + "@example.com",
			"hash",
			"owner"
		)));
	}

	private LearningMaterial material(User owner, String name) {
		return materialRepository.saveAndFlush(LearningMaterial.create(
			owner,
			name,
			"materials/" + name + "-" + UUID.randomUUID() + ".pdf"
		));
	}

	private void touch(Long materialId, Instant updatedAt) {
		jdbcTemplate.update(
			"update learning_materials set updated_at = ? where id = ?",
			Timestamp.from(updatedAt),
			materialId
		);
	}

	private void assertMaterial(
		Long materialId,
		MaterialStatus status,
		MaterialProcessingStatus processingStatus,
		MaterialFailureReason failureReason
	) {
		LearningMaterial material = materialRepository.findById(materialId)
			.orElseThrow();
		assertThat(material.getStatus()).isEqualTo(status);
		assertThat(material.getProcessingStatus()).isEqualTo(processingStatus);
		assertThat(material.getFailureReason()).isEqualTo(failureReason);
	}
}
