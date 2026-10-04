package io.edupilot.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.ai.AiClient;
import io.edupilot.auth.RefreshTokenService;
import io.edupilot.deletion.DeletionJournal;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.mail.EmailService;
import io.edupilot.user.dto.UpdatePreferencesRequest;
import io.edupilot.user.dto.UpdateProfileRequest;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:account-mutation-withdrawal;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/account-mutation-withdrawal",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging"
})
@ActiveProfiles("jpa-context")
class UserMutationWithdrawalRaceJpaTest {
	@DynamicPropertySource
	static void optionalIsolatedMysql(DynamicPropertyRegistry settings) {
		String url = System.getenv("ACCOUNT_MUTATION_MYSQL_URL");
		if (url == null || url.isBlank()) return;
		if (!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/account_mutation_synthetic(?:\\?.*)?$")) {
			throw new IllegalArgumentException("Account mutation tests require the disposable loopback database");
		}
		settings.add("spring.datasource.url", () -> url);
		settings.add("spring.datasource.username", () -> "root");
		settings.add("spring.datasource.password", () -> "");
		settings.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}
	private static final String PASSWORD = "SyntheticPass123!";
	private static final String NEW_PASSWORD = "DifferentSyntheticPass456!";
	@Autowired private UserRepository users;
	@Autowired private UserService service;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private JdbcTemplate jdbc;
	@MockitoSpyBean private PasswordEncoder passwords;
	@MockitoBean private AiClient ai;
	@MockitoBean private EmailService mail;
	@MockitoBean private RefreshTokenService refresh;
	@MockitoBean private DeletionJournal deletion;

	@ParameterizedTest
	@EnumSource(Mutation.class)
	void cachedActiveReadCannotAuthorizeAMutationAfterCommittedWithdrawal(Mutation mutation) throws Exception {
		Long userId = account();
		try (var executor = Executors.newSingleThreadExecutor()) {
			new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
				assertThat(service.me(userId).name()).isEqualTo("Original synthetic name");
				try {
					executor.submit(() -> service.withdraw(userId, PASSWORD)).get(15, TimeUnit.SECONDS);
				} catch (Exception failure) {
					throw new IllegalStateException("Synthetic withdrawal did not commit", failure);
				}
				assertThatThrownBy(() -> mutate(mutation, userId))
					.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.USER_INACTIVE));
				transaction.setRollbackOnly();
			});
		}
		assertAnonymous(userId);
	}

	@ParameterizedTest
	@EnumSource(Outcome.class)
	void pendingProfileWriteCannotRestoreFieldsAfterWithdrawal(Outcome outcome) throws Exception {
		Long userId = account();
		CountDownLatch changed = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var mutation = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
				mutate(Mutation.PROFILE, userId);
				changed.countDown();
				await(release);
				if (outcome == Outcome.ROLLBACK) transaction.setRollbackOnly();
			}));
			try {
				assertThat(changed.await(15, TimeUnit.SECONDS)).isTrue();
				var withdrawal = executor.submit(() -> service.withdraw(userId, PASSWORD));
				try {
					withdrawal.get(1, TimeUnit.SECONDS);
				} catch (TimeoutException serialized) {
					// A correctly locked mutation keeps withdrawal pending until commit/rollback.
				} finally {
					release.countDown();
				}
				mutation.get(15, TimeUnit.SECONDS);
				withdrawal.get(15, TimeUnit.SECONDS);
			} finally {
				release.countDown();
			}
		}
		assertAnonymous(userId);
	}

	@ParameterizedTest
	@EnumSource(Outcome.class)
	void passwordCheckStartedBeforeWithdrawalCannotRestoreCredential(Outcome outcome) throws Exception {
		Long userId = account();
		CountDownLatch read = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<Thread> mutator = new AtomicReference<>();
		doAnswer(invocation -> {
			if (Thread.currentThread() == mutator.get()) {
				read.countDown();
				await(release);
			}
			return invocation.callRealMethod();
		}).when(passwords).matches(eq(PASSWORD), anyString());
		try (var executor = Executors.newFixedThreadPool(2)) {
			var mutation = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
				mutator.set(Thread.currentThread());
				mutate(Mutation.PASSWORD, userId);
				if (outcome == Outcome.ROLLBACK) transaction.setRollbackOnly();
			}));
			try {
				assertThat(read.await(15, TimeUnit.SECONDS)).isTrue();
				var withdrawal = executor.submit(() -> {
					try {
						service.withdraw(userId, PASSWORD);
						return true;
					} catch (BusinessException changedCredential) {
						assertThat(outcome).isEqualTo(Outcome.COMMIT);
						assertThat(changedCredential.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
						return false;
					}
				});
				try {
					withdrawal.get(1, TimeUnit.SECONDS);
				} catch (TimeoutException serialized) {
					// Keep the password-check race separate from the already flushed update.
				} finally {
					release.countDown();
				}
				mutation.get(15, TimeUnit.SECONDS);
				if (!withdrawal.get(15, TimeUnit.SECONDS)) {
					User changed = users.findById(userId).orElseThrow();
					assertThat(changed.isActive()).isTrue();
					assertThat(passwords.matches(NEW_PASSWORD, changed.getPasswordHash())).isTrue();
					service.withdraw(userId, NEW_PASSWORD);
				}
			} finally {
				release.countDown();
			}
		}
		assertAnonymous(userId);
	}

	@Test
	void repeatedMutationsInOneTransactionPreserveEarlierUnflushedChanges() {
		Long userId = account();
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			service.updateProfile(userId, new UpdateProfileRequest("First synthetic name", "First affiliation"));
			service.updatePreferences(userId, new UpdatePreferencesRequest(false, null, AiAnswerStyle.DETAILED));
			service.updateProfile(userId, new UpdateProfileRequest(null, "Final affiliation"));
		});
		User updated = users.findById(userId).orElseThrow();
		assertThat(updated.getName()).isEqualTo("First synthetic name");
		assertThat(updated.getAffiliation()).isEqualTo("Final affiliation");
		assertThat(service.preferences(userId).newMaterialNotification()).isFalse();
		assertThat(service.preferences(userId).aiAnswerStyle()).isEqualTo(AiAnswerStyle.DETAILED);
	}

	private Long account() {
		if (System.getenv("ACCOUNT_MUTATION_MYSQL_URL") != null) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("account_mutation_synthetic");
		}
		return users.saveAndFlush(User.create(UUID.randomUUID() + "@example.test",
			passwords.encode(PASSWORD), "Original synthetic name")).getId();
	}

	private void mutate(Mutation mutation, Long userId) {
		switch (mutation) {
			case PROFILE -> service.updateProfile(userId, new UpdateProfileRequest("Late synthetic name", "Late affiliation"));
			case PREFERENCES -> service.updatePreferences(userId, new UpdatePreferencesRequest(false, false, AiAnswerStyle.DETAILED));
			case PASSWORD -> service.changePassword(userId, PASSWORD, NEW_PASSWORD);
			case AVATAR_DELETE -> service.deleteAvatar(userId);
			case WITHDRAWAL -> service.withdraw(userId, PASSWORD);
		}
	}

	private void assertAnonymous(Long userId) {
		User user = users.findById(userId).orElseThrow();
		assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
		assertThat(user.getName()).isEqualTo("탈퇴 사용자");
		assertThat(user.getAffiliation()).isNull();
		assertThat(user.getPasswordHash()).isEqualTo("!withdrawn:" + userId);
		assertThat(user.getEmail()).isEqualTo("deleted_" + userId);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic race barrier timed out");
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

	private enum Mutation { PROFILE, PREFERENCES, PASSWORD, AVATAR_DELETE, WITHDRAWAL }
	private enum Outcome { COMMIT, ROLLBACK }
}
