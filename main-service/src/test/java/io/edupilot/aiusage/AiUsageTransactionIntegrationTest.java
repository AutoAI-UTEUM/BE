package io.edupilot.aiusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.AiUsage;

@SpringBootTest(
	classes = AiUsageTransactionIntegrationTest.TestApplication.class,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:ai-usage-tx;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop"
	}
)
class AiUsageTransactionIntegrationTest {

	@Autowired
	private AiUsageLogRepository repository;
	@Autowired
	private AiUsageService usageService;
	@Autowired
	private RollbackCaller rollbackCaller;

	@BeforeEach
	void clearLogs() {
		repository.deleteAll();
	}

	@Test
	void requiresNewRecordSurvivesCallerRollback() {
		assertThatThrownBy(() -> rollbackCaller.recordThenRollback())
			.isInstanceOf(IllegalStateException.class);

		assertThat(repository.findAll()).singleElement().satisfies(log -> {
			assertThat(log.getUserId()).isEqualTo(1L);
			assertThat(log.getFeature()).isEqualTo(AiFeature.TURN);
			assertThat(log.getModel()).isEqualTo("grok-4");
			assertThat(log.isSuccess()).isTrue();
		});
	}

	@Test
	void databaseFailureDoesNotEscapeRecordBoundary() {
		String overlongModel = "x".repeat(51);

		assertThatCode(() -> usageService.record(
			1L,
			AiFeature.TURN,
			new AiUsage(overlongModel, 10L, 20L, null),
			true
		)).doesNotThrowAnyException();
		assertThat(repository.findAll()).isEmpty();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableJpaRepositories(basePackageClasses = AiUsageLogRepository.class)
	@Import(AiUsageService.class)
	static class TestApplication {

		@Bean
		RollbackCaller rollbackCaller(AiUsageService usageService) {
			return new RollbackCaller(usageService);
		}
	}

	static class RollbackCaller {

		private final AiUsageService usageService;

		RollbackCaller(AiUsageService usageService) {
			this.usageService = usageService;
		}

		@Transactional
		public void recordThenRollback() {
			usageService.record(
				1L,
				AiFeature.TURN,
				new AiUsage("grok-4", 10L, 20L, null),
				true
			);
			throw new IllegalStateException("caller rollback");
		}
	}
}
