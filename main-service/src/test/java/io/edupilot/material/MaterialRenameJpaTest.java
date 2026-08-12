package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:material-rename;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/material-rename"
	}
)
@ActiveProfiles("jpa-context")
class MaterialRenameJpaTest {

	@Autowired private UserRepository userRepository;
	@Autowired private LearningMaterialRepository materialRepository;
	@Autowired private MaterialService materialService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@MockitoBean private MaterialExtractionRecoveryScheduler recoveryScheduler;

	@Test
	void renamePersistsTitleAndAdvancesUpdatedAt() {
		User owner = userRepository.saveAndFlush(User.create(
			"material-rename@example.com",
			"hash",
			"owner"
		));
		LearningMaterial material = LearningMaterial.create(
			owner,
			"기존 제목",
			"materials/rename.pdf"
		);
		material.markReady(3);
		material = materialRepository.saveAndFlush(material);
		Instant oldUpdatedAt = Instant.parse("2020-01-01T00:00:00Z");
		jdbcTemplate.update(
			"update learning_materials set updated_at = ? where id = ?",
			Timestamp.from(oldUpdatedAt),
			material.getId()
		);

		var response = materialService.rename(
			owner.getId(),
			material.getId(),
			"  수정된 제목  "
		);

		LearningMaterial renamed = materialRepository.findById(material.getId())
			.orElseThrow();
		Instant updatedAt = jdbcTemplate.queryForObject(
			"select updated_at from learning_materials where id = ?",
			Timestamp.class,
			material.getId()
		).toInstant();
		assertThat(response.title()).isEqualTo("수정된 제목");
		assertThat(renamed.getTitle()).isEqualTo("수정된 제목");
		assertThat(renamed.getStorageKey()).isEqualTo("materials/rename.pdf");
		assertThat(renamed.getPageCount()).isEqualTo(3);
		assertThat(renamed.getProcessingStatus())
			.isEqualTo(MaterialProcessingStatus.READY);
		assertThat(updatedAt).isAfter(oldUpdatedAt);
	}
}
