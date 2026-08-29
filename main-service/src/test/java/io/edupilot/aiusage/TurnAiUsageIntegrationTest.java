package io.edupilot.aiusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiClientProperties;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.ai.dto.ExtractResponse;
import io.edupilot.ai.dto.ExtractedPage;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.material.MaterialCaptionTaskDispatcher;
import io.edupilot.material.MaterialExtractionPersistenceService;
import io.edupilot.material.MaterialExtractionPersistenceService.CompletionResult;
import io.edupilot.material.MaterialExtractionPersistenceService.ExtractionSnapshot;
import io.edupilot.material.MaterialExtractionService;
import io.edupilot.material.MaterialOutlineTaskDispatcher;
import io.edupilot.material.MaterialProperties;
import io.edupilot.material.MaterialXaiFileLifecycleService;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.memory.LearnerMemoryPromotionService;
import io.edupilot.session.PageStatus;
import io.edupilot.session.PersistedTurn;
import io.edupilot.session.PreparedTurn;
import io.edupilot.session.SessionStreamService;
import io.edupilot.session.SessionTurnService;
import io.edupilot.session.TurnClaimService;
import io.edupilot.session.TurnPersistenceService;
import io.edupilot.session.TurnPreparationService;
import io.edupilot.session.TurnResponseValidator;
import io.edupilot.session.TurnSnapshot;
import io.edupilot.session.TurnSnapshotService;
import io.edupilot.session.dto.TurnRequest;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.session.dto.TurnStateResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
	classes = TurnAiUsageIntegrationTest.TestApplication.class,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:turn-ai-usage;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop"
	}
)
class TurnAiUsageIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-29T01:23:45Z");

	@Autowired
	private SessionTurnService turnService;
	@Autowired
	private AiUsageLogRepository usageRepository;
	@Autowired
	private AiQuotaService quotaService;
	@Autowired
	private MaterialExtractionService extractionService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private TurnClaimService claimService;
	@MockitoBean
	private TurnPreparationService preparationService;
	@MockitoBean
	private TurnSnapshotService snapshotService;
	@MockitoBean
	private AiClient aiClient;
	@MockitoBean
	private TurnResponseValidator responseValidator;
	@MockitoBean
	private TurnPersistenceService persistenceService;
	@MockitoBean
	private LearnerMemoryPromotionService memoryPromotionService;
	@MockitoBean
	private SessionStreamService streamService;
	@MockitoBean
	private AiClientProperties aiClientProperties;
	@MockitoBean
	private UserRepository userRepository;
	@MockitoBean
	private MaterialAccessService materialAccessService;
	@MockitoBean
	private MaterialExtractionPersistenceService extractionPersistenceService;
	@MockitoBean
	private FileStorage fileStorage;
	@MockitoBean
	private MaterialOutlineTaskDispatcher outlineTaskDispatcher;
	@MockitoBean
	private MaterialCaptionTaskDispatcher captionTaskDispatcher;
	@MockitoBean
	private MaterialXaiFileLifecycleService xaiFileLifecycleService;

	@BeforeEach
	void setUpTurn() {
		usageRepository.deleteAll();
		when(aiClientProperties.turnReadTimeout())
			.thenReturn(Duration.ofSeconds(200));
		when(userRepository.findById(1L)).thenReturn(Optional.of(
			User.create("learner@example.com", "hash", "학습자")
		));
		when(preparationService.prepare(
			1L,
			100L,
			"request-1",
			"질문",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L, true))
			.thenReturn(new TurnSnapshot(
				Map.of("sessionId", 100L),
				Map.of(),
				10L
			));
		when(streamService.beginTurn(eq(1L), eq(100L), any()))
			.thenReturn(Optional.empty());
		when(persistenceService.persist(
			eq(1L),
			eq(100L),
			eq("request-1"),
			any(),
			eq(null),
			eq(501L),
			any()
		)).thenReturn(persisted());
	}

	@Test
	void successfulTurnPersistsUsageTokens() {
		when(aiClient.executeTurn(any(), any(Duration.class)))
			.thenReturn(aiResponse(new AiUsage("grok-4", 10L, 20L, 3L)));

		turnService.execute(1L, 100L, request());

		assertThat(usageRepository.findAll()).singleElement().satisfies(log -> {
			assertThat(log.getUserId()).isEqualTo(1L);
			assertThat(log.getFeature()).isEqualTo(AiFeature.TURN);
			assertThat(log.getModel()).isEqualTo("grok-4");
			assertThat(log.getInputTokens()).isEqualTo(10L);
			assertThat(log.getOutputTokens()).isEqualTo(20L);
			assertThat(log.getReasoningTokens()).isEqualTo(3L);
			assertThat(log.isSuccess()).isTrue();
		});
	}

	@Test
	void failedTurnPersistsFailureAndKeepsOriginalException() {
		AiClientException original = new AiClientException(
			ErrorCode.AI_SERVICE_TIMEOUT,
			false,
			null
		);
		when(aiClient.executeTurn(any(), any(Duration.class)))
			.thenThrow(original);

		assertThatThrownBy(() -> turnService.execute(1L, 100L, request()))
			.isSameAs(original);
		assertThat(usageRepository.findAll()).singleElement().satisfies(log -> {
			assertThat(log.getFeature()).isEqualTo(AiFeature.TURN);
			assertThat(log.isSuccess()).isFalse();
			assertThat(log.getModel()).isNull();
		});
	}

	@Test
	void quotaExceededReturns429ErrorWithoutCallingAi() {
		insertUsage(200, Instant.parse("2026-08-29T00:00:00Z"));

		assertThatThrownBy(() -> turnService.execute(1L, 100L, request()))
			.isInstanceOfSatisfying(BusinessException.class, exception -> {
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
				assertThat(exception.errorCode().status().value()).isEqualTo(429);
			});
		verify(aiClient, never()).executeTurn(any(), any(Duration.class));
		assertThat(usageRepository.count()).isEqualTo(200L);
	}

	@Test
	void usageBeforeKstMidnightDoesNotConsumeTodayQuota() {
		insertUsage(500, Instant.parse("2026-08-28T14:59:59Z"));
		insertUsage(199, Instant.parse("2026-08-28T15:00:00Z"));

		org.assertj.core.api.Assertions.assertThatCode(() ->
			quotaService.checkQuota(1L, io.edupilot.user.UserRole.LEARNER)
		).doesNotThrowAnyException();
	}

	@Test
	void extractBypassesExceededQuotaAndStillRecordsUsage() {
		insertUsage(200, Instant.parse("2026-08-29T00:00:00Z"));
		ByteArrayResource resource = new ByteArrayResource(
			"%PDF-test".getBytes()
		);
		List<ExtractedPage> pages = List.of(new ExtractedPage(1, "page"));
		when(extractionPersistenceService.snapshot(10L)).thenReturn(Optional.of(
			new ExtractionSnapshot(10L, 1L, "materials/key.pdf")
		));
		when(fileStorage.load("materials/key.pdf")).thenReturn(resource);
		when(aiClient.extract(resource)).thenReturn(new ExtractResponse(
			"1.0",
			1,
			pages,
			"file-new",
			List.of()
		));
		when(extractionPersistenceService.complete(10L, pages, "file-new"))
			.thenReturn(new CompletionResult(true, null));

		extractionService.extract(10L, "trace-1");

		verify(aiClient).extract(resource);
		assertThat(usageRepository.findAll())
			.filteredOn(log -> log.getFeature() == AiFeature.EXTRACT)
			.singleElement()
			.satisfies(log -> {
				assertThat(log.getUserId()).isEqualTo(1L);
				assertThat(log.isSuccess()).isTrue();
			});
		assertThat(usageRepository.count()).isEqualTo(201L);
	}

	private TurnRequest request() {
		return new TurnRequest(
			"request-1",
			"USER_QUESTION",
			new ObjectMapper().createObjectNode().put("message", "질문")
		);
	}

	private io.edupilot.ai.dto.TurnResponse aiResponse(AiUsage usage) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-upstream",
			"ANSWER_USER_QUESTION",
			List.of(),
			List.of(),
			Map.of(),
			List.of(),
			null,
			List.of(),
			null,
			usage
		);
	}

	private PersistedTurn persisted() {
		TurnResponse response = new TurnResponse(
			"turn-upstream",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(1, PageStatus.EXPLAINED, null)
		);
		return new PersistedTurn(
			response.turnId(),
			response.sessionId(),
			response.messages(),
			response.uiActions(),
			response.state(),
			null,
			10L
		);
	}

	private void insertUsage(int count, Instant createdAt) {
		Timestamp timestamp = Timestamp.from(createdAt);
		for (int index = 0; index < count; index++) {
			jdbcTemplate.update(
				"""
					insert into ai_usage_log
					(user_id, feature, success, created_at)
					values (?, ?, ?, ?)
					""",
				1L,
				AiFeature.TURN.name(),
				true,
				timestamp
			);
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableJpaRepositories(basePackageClasses = AiUsageLogRepository.class)
	@Import({
		AiUsageService.class,
		AiQuotaService.class,
		SessionTurnService.class,
		MaterialExtractionService.class
	})
	static class TestApplication {

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		AiQuotaProperties quotaProperties() {
			return new AiQuotaProperties(true, 200, 500);
		}

		@Bean
		MaterialProperties materialProperties() {
			return new MaterialProperties(45, 300, Duration.ofMinutes(30));
		}
	}
}
