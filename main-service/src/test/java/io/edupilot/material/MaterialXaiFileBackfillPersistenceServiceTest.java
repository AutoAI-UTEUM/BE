package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class MaterialXaiFileBackfillPersistenceServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

	@Mock
	private LearningMaterialRepository materialRepository;

	private MaterialXaiFileBackfillPersistenceService service;

	@BeforeEach
	void setUp() {
		service = new MaterialXaiFileBackfillPersistenceService(
			materialRepository,
			new MaterialXaiFileBackfillProperties(
				true,
				2,
				Duration.ofHours(6)
			),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	void findsOnlyBoundedCandidatesPastRetryCutoff() {
		when(materialRepository.findXaiFileBackfillIds(
			NOW.minus(Duration.ofHours(6)),
			PageRequest.of(0, 2)
		)).thenReturn(List.of(10L, 11L));

		assertThat(service.findCandidates()).containsExactly(10L, 11L);

		verify(materialRepository).findXaiFileBackfillIds(
			NOW.minus(Duration.ofHours(6)),
			PageRequest.of(0, 2)
		);
	}

	@Test
	void claimStampsAttemptAndRecentSecondClaimIsRejected() {
		LearningMaterial material = readyMaterial();
		when(materialRepository.findByIdForUpdate(10L))
			.thenReturn(Optional.of(material));

		assertThat(service.claim(10L)).isPresent();
		assertThat(material.getXaiFileUploadAttemptedAt()).isEqualTo(NOW);
		assertThat(service.claim(10L)).isEmpty();
	}

	@Test
	void attachNeverReplacesExistingFileId() {
		LearningMaterial material = readyMaterial();
		material.replaceXaiFileId("file-existing");
		when(materialRepository.findByIdForUpdate(10L))
			.thenReturn(Optional.of(material));

		assertThat(service.attachIfStillEligible(10L, "file-new")).isFalse();
		assertThat(material.getXaiFileId()).isEqualTo("file-existing");
	}

	private LearningMaterial readyMaterial() {
		LearningMaterial material = LearningMaterial.create(
			User.create("owner@example.com", "hash", "owner"),
			"material",
			"materials/key.pdf"
		);
		material.markReady(3);
		return material;
	}
}
