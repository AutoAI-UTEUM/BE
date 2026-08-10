package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
	@Autowired private EntityManager entityManager;

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
}
