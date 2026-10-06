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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

	@Test
	void duplicateRequestIdKeepsOriginalUsageRow() {
		usageService.record(
			1L,
			AiFeature.TURN,
			new AiUsage("grok-4", 10L, 20L, null, 100L),
			true,
			"request-1"
		);
		usageService.record(
			1L,
			AiFeature.TURN,
			new AiUsage("grok-4", 30L, 40L, null, 200L),
			true,
			"request-1"
		);

		assertThat(repository.findAll()).singleElement().satisfies(log -> {
			assertThat(log.getRequestId()).isEqualTo("request-1");
			assertThat(log.getCostUsdTicks()).isEqualTo(100L);
		});
	}

	@Test
	void concurrentRecordsForOneExecutionRemainOneRow() throws Exception {
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			java.util.concurrent.Callable<Void> record = () -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Synthetic record barrier timed out");
				}
				usageService.record(1L, AiFeature.TURN,
					new AiUsage("synthetic-model", 10L, 20L, null, 123L), true, "server-execution-1");
				return null;
			};
			var first = executor.submit(record);
			var second = executor.submit(record);
			try {
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			} finally {
				start.countDown();
			}
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
		}
		assertThat(repository.findAll()).singleElement().satisfies(log -> {
			assertThat(log.getRequestId()).isEqualTo("server-execution-1");
			assertThat(log.getCostUsdTicks()).isEqualTo(123L);
		});
	}


	@Test
	void policyRejectionRefinesOnlyOwnedTurnWithoutChangingCountCostOrTimestamp() {
		usageService.record(1L, AiFeature.TURN,
			new AiUsage("synthetic-model", 10L, 20L, null, 123L), true, "owned-execution");
		usageService.record(1L, AiFeature.EXTRACT, null, true, "other-feature");
		var before = repository.findAll().stream()
			.filter(log -> "owned-execution".equals(log.getRequestId())).findFirst().orElseThrow();

		usageService.markTurnPolicyRejected(2L, "owned-execution");
		usageService.markTurnPolicyRejected(1L, "other-feature");
		assertThat(repository.findAll()).allSatisfy(log -> assertThat(log.isSuccess()).isTrue());
		usageService.markTurnPolicyRejected(1L, "owned-execution");
		assertThat(repository.count()).isEqualTo(2);
		assertThat(repository.findById(before.getId()).orElseThrow()).satisfies(log -> {
			assertThat(log.isSuccess()).isFalse();
			assertThat(log.getCostUsdTicks()).isEqualTo(123L);
			assertThat(log.getCreatedAt()).isEqualTo(before.getCreatedAt());
		});
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
