package io.edupilot.composer;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.ai.dto.AiUsage;
import io.edupilot.aiusage.*;
import io.edupilot.material.*;
import io.edupilot.session.*;
import io.edupilot.user.*;

@SpringBootTest(classes = ComposerJpaTest.TestApplication.class, properties = {
	"spring.datasource.url=jdbc:h2:mem:composer;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
	"edupilot.composer.enabled=true"
})
class ComposerJpaTest {
	@Autowired UserRepository users;
	@Autowired AiUsageLogRepository usage;
	@Autowired ComposerAdmissionService admissions;
	@Autowired ChatMessageRepository messages;
	@Autowired QaMessageRepository qaMessages;
	@Autowired QaThreadRepository qaThreads;
	@Autowired LearningSessionRepository sessions;
	@Autowired LearningMaterialRepository materials;
	@Autowired PlatformTransactionManager transactions;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean AiQuotaService quota;
	User user;

	@BeforeEach void setup() {
		user = users.saveAndFlush(User.create("synthetic-" + UUID.randomUUID() + "@example.invalid", "synthetic-hash", "Synthetic learner"));
	}
	@Test void concurrentAdmissionsAcrossInstancesShareDatabaseLeaseAndDedup() throws Exception {
		var ready = new CountDownLatch(6); var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(6)) {
			var calls = IntStream.range(0, 6).mapToObj(i -> executor.submit(() -> {
				ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic barrier timeout");
				// Independent service objects share only the DB, as separate nodes would.
				var node = new ComposerAdmissionService(users, usage, quota, ComposerAdmissionServiceTest.policy(), Clock.systemUTC());
				return new TransactionTemplate(transactions).execute(ignored -> node.reserve(
					user.getId(), UserRole.LEARNER, "key-" + user.getId() + "-" + i));
			})).toList();
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
			int accepted = 0; for (var call : calls) if (call.get(10, TimeUnit.SECONDS)) accepted++;
			assertThat(accepted).isEqualTo(1);
		}
		assertThat(usage.findComposerUsage(user.getId(), Instant.EPOCH)).hasSize(1);
	}
	@Test void reservationSurvivesCallerRollbackAndKnownSpendRefinesSameRow() {
		String key = "key-" + user.getId();
		assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(ignored -> {
			assertThat(admissions.reserve(user.getId(), UserRole.ADMIN, key)).isTrue();
			throw new IllegalStateException("synthetic caller rollback");
		})).isInstanceOf(IllegalStateException.class);
		var before = usage.findByUserIdAndFeatureAndRequestId(user.getId(), AiFeature.COMPOSER_PREDICTION, key).orElseThrow();
		admissions.finish(user.getId(), key, new AiUsage("synthetic", 10L, 3L, null, 20L), false);
		var after = usage.findById(before.getId()).orElseThrow();
		assertThat(after.getCostUsdTicks()).isEqualTo(20L); assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
		// Expire only the synthetic concurrency lease; unique request ID still prevents replay.
		jdbc.update("update ai_usage_log set created_at=? where id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(100)), before.getId());
		assertThat(admissions.reserve(user.getId(), UserRole.LEARNER, key)).isFalse();
	}
	@Test void publicQueryExcludesAssessmentAnswersPrivateAndForeignHistory() {
		new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
			var material = LearningMaterial.create(user, "Synthetic PDF", "synthetic/" + UUID.randomUUID());
			material.markReady(3); materials.saveAndFlush(material);
			var session = sessions.saveAndFlush(LearningSession.create(user, material));
			var foreign = sessions.saveAndFlush(LearningSession.create(user, material));
			messages.saveAndFlush(ChatMessage.ai(foreign, MessageType.QA, "foreign-session"));
			messages.saveAndFlush(ChatMessage.ai(session, MessageType.QA, "old-page"));
			session.moveTo(2, PageStatus.EXPLAINED, List.of(UiAction.moveNextPage())); sessions.flush();
			var privateAnswer = messages.saveAndFlush(ChatMessage.user(session, "assessment-answer", "assessment"));
			messages.saveAndFlush(ChatMessage.ai(session, MessageType.DIAGNOSIS, "diagnosis-prompt"));
			messages.saveAndFlush(ChatMessage.ai(session, MessageType.REPAIR, "repair-solution"));
			messages.saveAndFlush(ChatMessage.ai(session, MessageType.SYSTEM, "internal-system"));
			var failed = ChatMessage.ai(session, MessageType.QA, "failed"); failed.markFailed(); messages.saveAndFlush(failed);
			var question = messages.saveAndFlush(ChatMessage.user(session, "public-question", "question"));
			var thread = qaThreads.saveAndFlush(QaThread.start(session));
			qaMessages.saveAndFlush(QaMessage.from(thread, question));
			messages.saveAndFlush(ChatMessage.ai(session, MessageType.QA, "public-answer"));
			assertThat(messages.findComposerMessages(session.getId(), 2, null, PageRequest.of(0, 6)))
				.extracting(ChatMessage::getContent).containsExactly("public-answer", "public-question");
			assertThat(messages.findComposerMessages(session.getId(), 2, Instant.now().plusSeconds(1), PageRequest.of(0, 6))).isEmpty();
			assertThat(privateAnswer.getRequestId()).isEqualTo("assessment");
		});
	}

	@Test void legacyFeatureValuesAndPredictionRoundTripThroughVarcharAndAdminJson() {
		assertThat(java.util.Arrays.stream(AiFeature.values())
			.filter(feature -> feature != AiFeature.COMPOSER_PREDICTION).map(Enum::name))
			.containsExactly("TURN", "DOC_CHAT", "GRADE", "QUIZ_ASSESSMENT", "DIAGNOSIS", "REPORT",
				"EXAM_DRAFT", "OUTLINE", "CAPTIONS", "CRITERIA", "EXTRACT");
		// V36 uses VARCHAR(40), not Hibernate's H2 native enum. Exercise that storage
		// contract in this disposable in-memory database, without claiming MySQL proof.
		jdbc.execute("alter table ai_usage_log alter column feature varchar(40)");
		var json = new tools.jackson.databind.ObjectMapper();
		for (AiFeature feature : AiFeature.values()) {
			String key = "compat-" + user.getId() + "-" + feature.name();
			usage.saveAndFlush(AiUsageLog.create(user.getId(), feature,
				new AiUsage("synthetic-model", 3L, 4L, null, 5L), true, key));
			var restored = usage.findByUserIdAndFeatureAndRequestId(user.getId(), feature, key).orElseThrow();
			assertThat(restored.getFeature()).isEqualTo(feature);
			assertThat(restored.getCostUsdTicks()).isEqualTo(5L);
			var dto = new io.edupilot.admin.dto.AdminAiUsageFeatureResponse(restored.getFeature(), 1L, 3L, 4L, null);
			assertThat(json.valueToTree(dto).get("feature").asString()).isEqualTo(feature.name());
		}
	}

	@SpringBootConfiguration @EnableAutoConfiguration
	@EntityScan("io.edupilot")
	@EnableJpaRepositories(basePackageClasses = {UserRepository.class, AiUsageLogRepository.class,
		LearningSessionRepository.class, LearningMaterialRepository.class})
	@Import(ComposerAdmissionService.class)
	static class TestApplication {
		@Bean Clock clock() { return Clock.systemUTC(); }
		@Bean ComposerProperties composerProperties() { return ComposerAdmissionServiceTest.policy(); }
	}
}
