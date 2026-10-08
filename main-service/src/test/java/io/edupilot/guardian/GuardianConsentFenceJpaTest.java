package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.global.error.BusinessException;
import io.edupilot.ai.AiClient;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.team.GuardianTeamProperties;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

/** 합성 H2 계정과 출력 표에서 실제 JPA 잠금 및 완료 트랜잭션을 확인합니다. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:guardian-fence;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/guardian-fence", "edupilot.mail.enabled=false", "edupilot.mail.provider=logging",
	// 아래 내용과 기간은 시험 전용이며 운영 동의문이나 보존 정책을 결정하지 않습니다.
	"edupilot.guardian.team.enabled=true", "edupilot.guardian.team.policy-confirmed=true",
	"edupilot.guardian.team.portal-base-url=https://synthetic.example.test",
	"edupilot.guardian.team.notice-url=https://synthetic.example.test/notice",
	"edupilot.guardian.team.notice-version=synthetic-1",
	"edupilot.guardian.team.notice-digest=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
	"edupilot.guardian.team.collection-items-text=합성 시험 정보",
	"edupilot.guardian.team.purposes-text=합성 시험 확인",
	"edupilot.guardian.team.retention-text=합성 시험용 보관 안내",
	"edupilot.guardian.team.refusal-text=합성 시험용 거부 안내",
	"edupilot.guardian.team.reply-contact=reviewer@synthetic.example.test",
	"edupilot.guardian.team.reply-channel=EMAIL_REPLY", "edupilot.guardian.team.reviewer-ids=1",
	"edupilot.guardian.team.required-scopes=SERVICE", "edupilot.guardian.team.optional-ai-scope=EXTERNAL_AI",
	"edupilot.guardian.team.optional-consent-text=합성 시험 외부 AI 선택 동의",
	"edupilot.guardian.team.link-ttl=PT1H", "edupilot.guardian.team.request-ttl=PT96H",
	"edupilot.guardian.team.approved-evidence-retention=P30D", "edupilot.guardian.team.approval-validity=P30D"
})
@ActiveProfiles("jpa-context")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuardianConsentFenceJpaTest {
	private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
	@Autowired private UserRepository users;
	@Autowired private GuardianConsentFence fence;
	@Autowired private GuardianTeamProperties policy;
	@Autowired private PlatformTransactionManager manager;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private Clock clock;
	@MockitoBean private AiClient ai;
	private User child;

	@BeforeEach
	void createCommittedSyntheticAccount() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		assertThat(policy.ready()).isTrue();
		jdbc.execute("create table if not exists guardian_fence_test_outputs (output_id varchar(36) primary key, payload varchar(100))");
		jdbc.update("delete from guardian_fence_test_outputs");
		child = createChild();
	}

	@Test
	void staleManagedAccountAfterReapprovalCannotSaveAndTheTransactionRollsBack() throws Exception {
		GuardianConsentFence.Snapshot consent = fence.capture(child.getId());
		AtomicBoolean saved = new AtomicBoolean();
		try (var pool = Executors.newSingleThreadExecutor()) {
			assertThatThrownBy(() -> transaction().execute(status -> {
				User stale = users.findById(child.getId()).orElseThrow();
				assertThat(stale.getGuardianConsentEpoch()).isEqualTo(consent.guardianConsentEpoch());
				try {
					pool.submit(() -> mutate(child.getId(), user -> {
						user.clearGuardianTeamApproval(false);
						approve(user, true);
					})).get(5, TimeUnit.SECONDS);
				} catch (Exception failure) { throw new IllegalStateException(failure); }
				// 이 영속성 컨텍스트의 엔티티는 여전히 이전 세대이며 Fence의 refresh가 이를 교체해야 합니다.
				assertThat(stale.getGuardianConsentEpoch()).isEqualTo(consent.guardianConsentEpoch());
				return fence.complete(consent, () -> { saved.set(true); return storeOutput(); });
			})).isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED));
		}
		assertThat(saved).isFalse();
		assertThat(outputCount()).isZero();
	}

	@ParameterizedTest
	@EnumSource(InvalidApproval.class)
	void incompleteExpiredOrDifferentPolicyApprovalNeverInvokesAnAiResultWriter(InvalidApproval invalid) {
		mutate(child.getId(), user -> {
			switch (invalid) {
				case NO_EXTERNAL_AI -> approve(user, false);
				case EXPIRED -> {
					user.recordGuardianTeamApproval(NOW, true);
					user.recordGuardianTeamPolicyDigest(policy.configurationDigest());
				}
				case POLICY_CHANGED -> user.recordGuardianTeamPolicyDigest("b".repeat(64));
			}
		});
		User current = users.findById(child.getId()).orElseThrow();
		GuardianConsentFence.Snapshot consent = new GuardianConsentFence.Snapshot(current.getId(), current.getGuardianConsentEpoch());
		AtomicBoolean saved = new AtomicBoolean();
		assertThatThrownBy(() -> fence.complete(consent, () -> { saved.set(true); return storeOutput(); }))
			.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(
				invalid == InvalidApproval.NO_EXTERNAL_AI ? ErrorCode.GUARDIAN_AI_CONSENT_REQUIRED : ErrorCode.AGE_VERIFICATION_REQUIRED));
		assertThat(saved).isFalse();
		assertThat(outputCount()).isZero();
	}

	@Test
	void twoSubjectCompletionHoldsAccountLocksUntilItsWriterCommitsBeforeRevocation() throws Exception {
		User second = createChild();
		GuardianConsentFence.Snapshot one = fence.capture(child.getId());
		GuardianConsentFence.Snapshot two = fence.capture(second.getId());
		CountDownLatch attempted = new CountDownLatch(1);
		try (var pool = Executors.newSingleThreadExecutor()) {
			var revoked = new java.util.concurrent.CompletableFuture<Void>();
			assertThat(fence.complete(List.of(two, one, two), () -> {
				pool.submit(() -> {
					attempted.countDown();
					try { mutate(child.getId(), user -> user.clearGuardianTeamApproval(false)); revoked.complete(null); }
					catch (Throwable failure) { revoked.completeExceptionally(failure); }
				});
				try {
					assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
					assertThatThrownBy(() -> revoked.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				} catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
				return storeOutput();
			})).isEqualTo(1);
			revoked.get(5, TimeUnit.SECONDS);
		}
		assertThat(outputCount()).isEqualTo(1);
		assertThatThrownBy(() -> fence.complete(List.of(one, two), this::storeOutput))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED));
		assertThat(outputCount()).isEqualTo(1);
	}

	private User createChild() {
		return transaction().execute(status -> {
			User user = User.create("synthetic-" + UUID.randomUUID() + "@example.test", "!synthetic", "합성 학습자");
			user.recordSignupDateOfBirth(LocalDate.of(2013, 1, 1));
			user.verifyEmail(NOW.minusSeconds(60));
			approve(user, true);
			return users.saveAndFlush(user);
		});
	}

	private void approve(User user, boolean externalAi) {
		user.recordGuardianTeamApproval(NOW.plusSeconds(3600), externalAi);
		user.recordGuardianTeamPolicyDigest(policy.configurationDigest());
	}

	private void mutate(Long userId, Consumer<User> change) {
		transaction().executeWithoutResult(status -> { change.accept(users.findByIdForUpdate(userId).orElseThrow()); users.flush(); });
	}

	private TransactionTemplate transaction() { return new TransactionTemplate(manager); }
	private int storeOutput() {
		return jdbc.update("insert into guardian_fence_test_outputs (output_id, payload) values (?, ?)", UUID.randomUUID().toString(), "합성 AI 결과");
	}
	private int outputCount() { return jdbc.queryForObject("select count(*) from guardian_fence_test_outputs", Integer.class); }
	private enum InvalidApproval { NO_EXTERNAL_AI, EXPIRED, POLICY_CHANGED }
}
