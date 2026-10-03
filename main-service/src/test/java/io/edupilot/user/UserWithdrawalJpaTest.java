package io.edupilot.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.ai.AiClient;
import io.edupilot.auth.AuthSession;
import io.edupilot.auth.AuthSessionRepository;
import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.auth.RefreshToken;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.auth.UserAccessGuard;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryResult;
import io.edupilot.mail.EmailDeliveryStatus;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailSender;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialStatus;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:withdrawal;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/withdrawal",
	"edupilot.mail.enabled=true", "edupilot.mail.provider=logging"
})
@ActiveProfiles("jpa-context")
class UserWithdrawalJpaTest {
	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private AuthSessionRepository authSessions;
	@Autowired private RefreshTokenRepository refreshTokens;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private UserService service;
	@Autowired private UserAccessGuard guard;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoBean private AiClient ai;
	@MockitoBean private EmailSender sender;
	private final CopyOnWriteArrayList<EmailMessage> sent = new CopyOnWriteArrayList<>();

	@BeforeEach
	void captureSyntheticMail() {
		sent.clear();
		when(sender.send(any())).thenAnswer(invocation -> {
			sent.add(invocation.getArgument(0));
			return new EmailDeliveryResult("synthetic-withdrawal-mail");
		});
	}

	@Test
	void committedGoogleWithdrawalAnonymizesRevokesAndSendsToOriginalAddress() throws Exception {
		Fixture f = fixture();
		var principal = new AuthenticatedUser(f.user(), UserRole.LEARNER);
		assertThat(guard.check(principal)).isNull();
		service.withdrawGoogle(f.user(), f.subject());
		awaitDelivery(f.email());
		User withdrawn = users.findById(f.user()).orElseThrow();
		assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.DELETED);
		assertThat(withdrawn.getGoogleSub()).isNull();
		assertThat(withdrawn.getEmail()).isEqualTo("deleted_" + f.user());
		assertThat(materials.findById(f.material()).orElseThrow().getStatus()).isEqualTo(MaterialStatus.DELETED);
		assertThat(sessions.findById(f.session()).orElseThrow().getStatus()).isEqualTo(SessionStatus.DELETED);
		assertThat(authSessions.findById(f.authSession()).orElseThrow().isRevoked()).isTrue();
		assertThat(refresh(f).isRevoked()).isTrue();
		assertThat(guard.check(principal)).isEqualTo(ErrorCode.TOKEN_INVALID);
		assertThat(sent).hasSize(1);
		assertThat(sent.getFirst().to()).isEqualTo(f.email());
		assertThat(sent.getFirst().textBody()).contains("파일의 정리 상태는 별도로 관리");
	}

	@Test
	void mismatchedSubjectAndPasswordFallbackLeaveGoogleAccountUnchanged() {
		Fixture f = fixture();
		assertThatThrownBy(() -> service.withdrawGoogle(f.user(), "other-subject"))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.withdraw(f.user(), "password123"))
			.isInstanceOf(BusinessException.class);
		assertThat(users.findById(f.user()).orElseThrow().isActive()).isTrue();
		assertThat(refresh(f).isRevoked()).isFalse();
		assertThat(sent).isEmpty();
	}

	@Test
	void rolledBackDeletionNeverSendsCompletionMailOrRevokesAccess() {
		Fixture f = fixture();
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			service.withdrawGoogle(f.user(), f.subject());
			assertThat(sent).isEmpty();
			transaction.setRollbackOnly();
		});
		assertThat(users.findById(f.user()).orElseThrow().isActive()).isTrue();
		assertThat(materials.findById(f.material()).orElseThrow().getStatus()).isEqualTo(MaterialStatus.ACTIVE);
		assertThat(authSessions.findById(f.authSession()).orElseThrow().isRevoked()).isFalse();
		assertThat(sent).isEmpty();
		assertThat(deliveries.findAll().stream().filter(d -> d.getRecipient().equals(f.email())))
			.singleElement().satisfies(d -> {
				assertThat(d.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
				assertThat(d.getErrorSummary()).isEqualTo("CALLER_TRANSACTION_ROLLED_BACK");
			});
	}

	@Test
	void concurrentWithdrawalHasOneCommittedCompletionAndOneMail() throws Exception {
		Fixture f = fixture();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			java.util.concurrent.Callable<Boolean> withdraw = () -> {
				start.await();
				try {
					service.withdrawGoogle(f.user(), f.subject());
					return true;
				} catch (BusinessException error) {
					assertThat(error.errorCode()).isEqualTo(ErrorCode.USER_INACTIVE);
					return false;
				}
			};
			var first = executor.submit(withdraw);
			var second = executor.submit(withdraw);
			start.countDown();
			assertThat(first.get(15, TimeUnit.SECONDS)).isNotEqualTo(second.get(15, TimeUnit.SECONDS));
		}
		awaitDelivery(f.email());
		assertThat(sent).hasSize(1);
	}

	private void awaitDelivery(String email) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			if (deliveries.findAll().stream().anyMatch(d -> d.getRecipient().equals(email)
				&& d.getStatus() == EmailDeliveryStatus.SENT)) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Synthetic withdrawal mail did not complete");
	}

	private RefreshToken refresh(Fixture fixture) {
		return refreshTokens.findAll().stream().filter(token -> token.getTokenHash().equals(fixture.refresh()))
			.findFirst().orElseThrow();
	}

	private Fixture fixture() {
		String subject = UUID.randomUUID().toString();
		String email = subject + "@example.com";
		User user = users.saveAndFlush(User.createGoogle(email, "!google", "Synthetic",
			UserRole.LEARNER, null, false, null, null, null, subject));
		LearningMaterial material = LearningMaterial.create(user, "Synthetic", "materials/" + subject + ".pdf");
		material.markReady(2);
		materials.saveAndFlush(material);
		LearningSession session = sessions.saveAndFlush(LearningSession.create(user, material));
		Instant now = Instant.now();
		AuthSession authSession = authSessions.saveAndFlush(AuthSession.create(user, now, Duration.ofHours(2), Duration.ofDays(14)));
		RefreshToken refresh = refreshTokens.saveAndFlush(new RefreshToken(user, authSession,
			"a".repeat(32) + subject.replace("-", ""), now.plus(Duration.ofDays(14))));
		return new Fixture(user.getId(), email, subject, material.getId(), session.getId(), authSession.getId(), refresh.getTokenHash());
	}

	private record Fixture(Long user, String email, String subject, Long material, Long session, Long authSession, String refresh) { }
}
