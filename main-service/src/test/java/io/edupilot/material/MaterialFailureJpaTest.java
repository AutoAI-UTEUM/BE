package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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

	@Autowired private UserRepository userRepository;
	@Autowired private LearningMaterialRepository materialRepository;
	@Autowired private MaterialExtractionPersistenceService persistenceService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManager entityManager;
	@MockitoBean private MaterialExtractionRecoveryScheduler recoveryScheduler;

	@Test
	void persistsFailureMetadataWithFailedMaterial() {
		User owner = userRepository.saveAndFlush(User.create(
			"material-failure@example.com",
			"hash",
			"owner"
		));
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
		return userRepository.saveAndFlush(User.create(
			"material-recovery-" + UUID.randomUUID() + "@example.com",
			"hash",
			"owner"
		));
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
