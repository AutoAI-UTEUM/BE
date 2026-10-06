package io.edupilot.aiusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.ChatMessageStatus;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionService;
import io.edupilot.session.SessionStreamService;
import io.edupilot.session.SessionTurnService;
import io.edupilot.session.dto.TurnRequest;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = io.edupilot.MainServiceApplication.class, properties = {
	"spring.datasource.url=jdbc:h2:mem:turn-usage-security;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/turn-usage-security",
	"edupilot.ai.quota.daily-default=2",
	"edupilot.ai.quota.daily-instructor=2"
})
@ActiveProfiles("jpa-context")
class TurnUsageSecurityJpaTest {
	private static final AiUsage USAGE = new AiUsage("synthetic-model", 10L, 20L, null, 123L);
	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private MaterialPageRepository pages;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private ChatMessageRepository messages;
	@Autowired private SessionService sessionService;
	@Autowired private SessionTurnService turns;
	@Autowired private SessionStreamService streams;
	@Autowired private AiUsageService usageService;
	@Autowired private AiUsageLogRepository usage;
	@Autowired private PlatformTransactionManager transactions;
	@MockitoBean private AiClient ai;
	@MockitoBean private Clock clock;

	private final List<String> executions = new CopyOnWriteArrayList<>();
	private final AtomicInteger attempts = new AtomicInteger();

	@BeforeEach
	void configureStub() {
		usage.deleteAll();
		Instant now = Instant.now();
		when(clock.instant()).thenReturn(now);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(clock.withZone(any())).thenAnswer(invocation ->
			Clock.fixed(now, invocation.getArgument(0, ZoneId.class)));
		when(ai.executeTurn(any(), any(Duration.class))).thenAnswer(invocation ->
			answer(invocation.getArgument(0)));
		when(ai.executeTurnStream(any(), any(), any(), any())).thenAnswer(invocation ->
			answer(invocation.getArgument(0)));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void completedSessionThenNewSessionWithSameRequestIdCountsEachExecution(boolean streaming) {
		Fixture f = fixture();
		execute(f, f.session(), "same-client-id", streaming);
		sessionService.complete(f.user(), f.session());
		long next = sessionService.create(f.user(), f.material()).sessionId();
		assertThat(next).isNotEqualTo(f.session());
		execute(f, next, "same-client-id", streaming);

		assertExecutions(2);
		assertThat(usage.findAll()).allSatisfy(log -> assertThat(log.getUserId()).isEqualTo(f.user()));
		sessionService.complete(f.user(), next);
		long third = sessionService.create(f.user(), f.material()).sessionId();
		assertError(() -> execute(f, third, "same-client-id", streaming), ErrorCode.AI_QUOTA_EXCEEDED);
		assertExecutions(2);
		assertThat(sessions.findById(third).orElseThrow().getActiveTurnRequestId()).isNull();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void successfulSameSessionReplayDoesNotStartAnotherExecution(boolean streaming) {
		Fixture f = fixture();
		execute(f, f.session(), "completed-request", streaming);
		assertError(() -> execute(f, f.session(), "completed-request", streaming),
			ErrorCode.TURN_ALREADY_PROCESSED);
		assertExecutions(1);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void validationFailureIsOneFailedRowWithKnownUsageAndSameRequestCanRecover(boolean streaming) {
		Fixture f = fixture();
		stubBoth(request -> {
			answer(request);
			return response(request.turnId(), Map.of("unrecognizedField", true));
		});
		assertError(() -> execute(f, f.session(), "failed-request", streaming),
			ErrorCode.AI_POLICY_REJECTED);
		ChatMessage failed = messages.findBySession_IdAndRequestId(f.session(), "failed-request")
			.orElseThrow();
		assertThat(failed.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
		assertThat(usage.findAll()).singleElement().satisfies(log -> {
			assertThat(log.isSuccess()).isFalse();
			assertThat(log.getCostUsdTicks()).isEqualTo(123L);
			assertThat(log.getInputTokens()).isEqualTo(10L);
		});
		long messageId = failed.getId();
		stubBoth(this::answer);
		execute(f, f.session(), "failed-request", streaming);
		assertExecutions(2);
		assertThat(messages.findBySession_IdAndRequestId(f.session(), "failed-request").orElseThrow())
			.satisfies(message -> {
				assertThat(message.getId()).isEqualTo(messageId);
				assertThat(message.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
			});
		assertThat(usage.findAll().stream().filter(AiUsageLog::isSuccess)).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void retryableFailureAndSuccessAreTwoDistinctExecutions(boolean streaming) {
		Fixture f = fixture();
		stubBoth(request -> {
			var response = answer(request);
			if (attempts.get() == 1) {
				throw new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT, true, null);
			}
			return response;
		});
		execute(f, f.session(), "retry-request", streaming);
		assertExecutions(2);
		assertThat(usage.findAll().stream().filter(AiUsageLog::isSuccess)).hasSize(1);
		assertThat(usage.findAll().stream().filter(log -> !log.isSuccess())).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void failedLastAllowedExecutionBlocksRetryBeforeAi(boolean streaming) {
		Fixture f = fixture();
		usageService.record(f.user(), AiFeature.TURN, null, true, "historical-execution");
		stubBoth(request -> {
			answer(request);
			throw new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT, true, null);
		});
		assertError(() -> execute(f, f.session(), "last-request", streaming),
			ErrorCode.AI_QUOTA_EXCEEDED);
		assertThat(attempts).hasValue(1);
		assertThat(usage.count()).isEqualTo(2);
		assertThat(usage.findAll().stream().filter(log -> !log.isSuccess())).hasSize(1);
		assertThat(sessions.findById(f.session()).orElseThrow().getActiveTurnRequestId()).isNull();
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 64, 65, 255})
	void externalRequestIdLengthDoesNotChangeExecutionKey(int length) {
		Fixture f = fixture();
		execute(f, f.session(), "a".repeat(length), false);
		assertExecutions(1);
		assertThat(messages.findBySession_IdAndRequestId(f.session(), "a".repeat(length))).isPresent();
	}

	@Test
	void simultaneousOwnedSessionsCountEveryExecutionAndBusyReplayDoesNotCallAi() throws Exception {
		Fixture first = fixture();
		Fixture second = fixture(first.user());
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		stubBoth(request -> {
			var result = answer(request);
			entered.countDown();
			if (!release.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Synthetic AI barrier timed out");
			}
			return result;
		});
		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> execute(first, first.session(), "parallel-id", false));
			var b = executor.submit(() -> execute(second, second.session(), "parallel-id", false));
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertError(() -> execute(first, first.session(), "parallel-id", false),
					ErrorCode.TURN_ALREADY_PROCESSED);
				assertThat(attempts).hasValue(2);
			} finally {
				release.countDown();
			}
			a.get(5, TimeUnit.SECONDS);
			b.get(5, TimeUnit.SECONDS);
		}
		assertExecutions(2);
	}

	@Test
	void differentUsersWithSameClientIdKeepSeparateUsage() {
		Fixture first = fixture();
		Fixture second = fixture();
		execute(first, first.session(), "shared-client-id", false);
		execute(second, second.session(), "shared-client-id", false);
		assertExecutions(2);
		assertThat(usage.findAll()).extracting(AiUsageLog::getUserId)
			.containsExactlyInAnyOrder(first.user(), second.user());
	}


	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void persistencePolicyRejectionKeepsOneFailedUsageRow(boolean streaming) {
		Fixture f = fixture();
		stubBoth(request -> {
			answer(request);
			return response(request.turnId(), Map.of());
		});
		assertError(() -> execute(f, f.session(), "persist-policy-rejected", streaming),
			ErrorCode.AI_POLICY_REJECTED);
		assertExecutions(1);
		assertThat(messages.findBySession_IdAndRequestId(f.session(), "persist-policy-rejected")
			.orElseThrow().getStatus()).isEqualTo(ChatMessageStatus.FAILED);
		assertThat(usage.findAll()).singleElement().satisfies(log -> {
			assertThat(log.isSuccess()).isFalse();
			assertThat(log.getCostUsdTicks()).isEqualTo(123L);
		});
	}

	private void execute(Fixture f, long session, String requestId, boolean streaming) {
		if (streaming) {
			streams.connect(f.user(), session);
		}
		turns.execute(f.user(), session, new TurnRequest(requestId, "USER_QUESTION",
			new ObjectMapper().createObjectNode().put("message", "Synthetic question")));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void transportValidationFailureKeepsDecodedUsageInOneFailedExecution(boolean streaming) {
		Fixture f = fixture();
		stubBoth(request -> {
			answer(request);
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID).withUsage(USAGE);
		});
		assertError(() -> execute(f, f.session(), "transport-rejected", streaming),
			ErrorCode.AI_RESPONSE_INVALID);
		assertExecutions(1);
		assertThat(usage.findAll()).singleElement().satisfies(log -> {
			assertThat(log.isSuccess()).isFalse();
			assertThat(log.getCostUsdTicks()).isEqualTo(123L);
		});
	}

	private io.edupilot.ai.dto.TurnResponse answer(io.edupilot.ai.dto.TurnRequest request) {
		executions.add(request.turnId());
		attempts.incrementAndGet();
		return response(request.turnId(), Map.of("qaThread", Map.of("mode", "START_NEW")));
	}

	private io.edupilot.ai.dto.TurnResponse response(String turnId, Map<String, Object> patch) {
		return new io.edupilot.ai.dto.TurnResponse("1.0", turnId, "ANSWER_USER_QUESTION",
			List.of(), List.of(Map.of("messageType", "QA", "content", "Synthetic answer")),
			patch, List.of(), null, List.of(), null, USAGE);
	}

	private void stubBoth(Answer answer) {
		doAnswer(invocation -> answer.respond(invocation.getArgument(0)))
			.when(ai).executeTurn(any(), any(Duration.class));
		doAnswer(invocation -> answer.respond(invocation.getArgument(0)))
			.when(ai).executeTurnStream(any(), any(), any(), any());
	}

	private Fixture fixture() {
		return fixture(users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com",
			"synthetic-hash", "Synthetic learner")).getId());
	}

	private Fixture fixture(long userId) {
		var transaction = new TransactionTemplate(transactions);
		long material = transaction.execute(status -> {
			var stored = materials.saveAndFlush(LearningMaterial.create(users.getReferenceById(userId),
				"Synthetic material", "materials/" + UUID.randomUUID() + ".pdf"));
			stored.markReady(1);
			pages.saveAndFlush(MaterialPage.create(stored, 1, "Synthetic lecture page text."));
			materials.flush();
			return stored.getId();
		});
		long session = sessionService.create(userId, material).sessionId();
		return new Fixture(userId, material, session);
	}

	private void assertExecutions(int count) {
		assertThat(attempts).hasValue(count);
		assertThat(usage.findAll()).hasSize(count)
			.extracting(AiUsageLog::getRequestId).containsExactlyInAnyOrderElementsOf(executions);
		assertThat(executions).doesNotHaveDuplicates()
			.allSatisfy(id -> assertThat(id).startsWith("turn-").hasSize(41));
	}

	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.errorCode()).isEqualTo(expected));
	}

	private record Fixture(long user, long material, long session) {
	}

	@FunctionalInterface
	private interface Answer {
		io.edupilot.ai.dto.TurnResponse respond(io.edupilot.ai.dto.TurnRequest request) throws Exception;
	}
}
