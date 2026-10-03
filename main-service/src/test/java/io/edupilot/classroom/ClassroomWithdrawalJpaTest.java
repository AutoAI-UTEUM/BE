package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.ai.AiClient;
import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.auth.UserAccessGuard;
import io.edupilot.classroom.dto.CreateClassroomRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.mail.EmailService;
import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserService;
import io.edupilot.user.UserStatus;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:classroom-withdrawal;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=synthetic-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/classroom-withdrawal"
})
@ActiveProfiles("jpa-context")
class ClassroomWithdrawalJpaTest {
	@DynamicPropertySource
	static void isolatedMysql(DynamicPropertyRegistry properties) {
		String url = System.getenv("CLASSROOM_WITHDRAWAL_MYSQL_URL");
		if (url == null || url.isBlank()) { return; }
		if (!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/classroom_withdrawal_synthetic(?:\\?.*)?$")) {
			throw new IllegalArgumentException("Withdrawal tests require the disposable loopback database");
		}
		properties.add("spring.datasource.url", () -> url);
		properties.add("spring.datasource.username", () -> "root");
		properties.add("spring.datasource.password", () -> "");
		properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}

	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ClassroomJoinRequestRepository joinRequests;
	@Autowired private UserService userService;
	@Autowired private ClassroomService classroomService;
	@Autowired private PasswordEncoder passwords;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private UserAccessGuard accessGuard;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private AiClient ai;
	@MockitoBean private EmailService mail;
	@MockitoBean private ClassroomInviteCodeGenerator inviteCodes;

	@BeforeEach
	void cleanSyntheticRows() {
		if (System.getenv("CLASSROOM_WITHDRAWAL_MYSQL_URL") != null) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("classroom_withdrawal_synthetic");
		}
		joinRequests.deleteAll(); members.deleteAll(); classrooms.deleteAll(); users.deleteAll();
	}

	@ParameterizedTest
	@EnumSource(AuthProvider.class)
	void withdrawalClosesOnlyOwnedActiveClassroomsAndKeepsHistoricalMembers(AuthProvider provider) {
		Fixture fixture = fixture(provider);
		var principal = new AuthenticatedUser(fixture.ownerId(), UserRole.INSTRUCTOR);
		assertThat(accessGuard.check(principal)).isNull();
		withdraw(fixture, provider);
		assertThat(users.findById(fixture.ownerId()).orElseThrow().getStatus()).isEqualTo(UserStatus.DELETED);
		assertThat(classrooms.findById(fixture.activeId()).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.COMPLETED);
		assertThat(classrooms.findById(fixture.completedId()).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.COMPLETED);
		assertThat(classrooms.findById(fixture.otherId()).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.ACTIVE);
		assertThat(classrooms.findById(fixture.activeId()).orElseThrow().getInstructorId()).isEqualTo(fixture.ownerId());
		assertThat(members.countByClassroom_Id(fixture.activeId())).isEqualTo(1);
		assertThat(accessGuard.check(principal)).isEqualTo(ErrorCode.TOKEN_INVALID);
		assertThatThrownBy(() -> classroomService.assertWritable(classrooms.findById(fixture.activeId()).orElseThrow()))
			.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CLASSROOM_COMPLETED));
	}

	@Test
	void accountAndClassroomClosureRollbackTogether() {
		Fixture fixture = fixture(AuthProvider.LOCAL);
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			userService.withdraw(fixture.ownerId(), "StrongPass123!");
			assertThat(classrooms.findById(fixture.activeId()).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.COMPLETED);
			transaction.setRollbackOnly();
		});
		assertThat(users.findById(fixture.ownerId()).orElseThrow().isActive()).isTrue();
		assertThat(classrooms.findById(fixture.activeId()).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.ACTIVE);
		assertThat(classrooms.findById(fixture.otherId()).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.ACTIVE);
		assertThat(members.countByClassroom_Id(fixture.activeId())).isEqualTo(1);
	}

	@Test
	void formerInstructorStillClosesTheClassroomsTheyOwn() {
		Fixture fixture = fixture(AuthProvider.LOCAL);
		new TransactionTemplate(transactions).executeWithoutResult(transaction ->
			users.findById(fixture.ownerId()).orElseThrow().changeRole(UserRole.LEARNER));
		userService.withdraw(fixture.ownerId(), "StrongPass123!");
		assertThat(classrooms.findById(fixture.activeId()).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.COMPLETED);
		assertThat(classrooms.findById(fixture.otherId()).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.ACTIVE);
	}

	@Test
	void failedReauthenticationDoesNotCloseClassrooms() {
		Fixture fixture = fixture(AuthProvider.LOCAL);
		assertThatThrownBy(() -> userService.withdraw(fixture.ownerId(), "wrong-password"))
			.isInstanceOf(BusinessException.class);
		assertThat(users.findById(fixture.ownerId()).orElseThrow().isActive()).isTrue();
		assertThat(classrooms.findById(fixture.activeId()).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.ACTIVE);
	}

	@Test
	void concurrentCreationFinishesBeforeWithdrawalAndTheNewClassroomIsAlsoClosed() throws Exception {
		Fixture fixture = fixture(AuthProvider.LOCAL);
		CountDownLatch creatorLockedOwner = new CountDownLatch(1);
		CountDownLatch allowInsert = new CountDownLatch(1);
		CountDownLatch withdrawalStarted = new CountDownLatch(1);
		when(inviteCodes.generate()).thenAnswer(invocation -> {
			creatorLockedOwner.countDown();
			assertThat(allowInsert.await(15, TimeUnit.SECONDS)).isTrue();
			return "RACE-NEW1";
		});
		try (var executor = Executors.newFixedThreadPool(2)) {
			var created = executor.submit(() -> classroomService.create(fixture.ownerId(), UserRole.INSTRUCTOR, request()).classroomId());
			try {
				assertThat(creatorLockedOwner.await(15, TimeUnit.SECONDS)).isTrue();
				var withdrawn = executor.submit(() -> {
					withdrawalStarted.countDown();
					userService.withdraw(fixture.ownerId(), "StrongPass123!");
					return null;
				});
				assertThat(withdrawalStarted.await(15, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> withdrawn.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				allowInsert.countDown();
				Long newId = created.get(15, TimeUnit.SECONDS);
				withdrawn.get(15, TimeUnit.SECONDS);
				assertThat(classrooms.findById(newId).orElseThrow().getStatus()).isEqualTo(ClassroomStatus.COMPLETED);
				assertThat(users.findById(fixture.ownerId()).orElseThrow().isActive()).isFalse();
			} finally { allowInsert.countDown(); }
		}
	}

	@Test
	void withdrawnOwnerCannotCreateAnotherActiveClassroom() {
		Fixture fixture = fixture(AuthProvider.LOCAL);
		userService.withdraw(fixture.ownerId(), "StrongPass123!");
		assertThatThrownBy(() -> classroomService.create(fixture.ownerId(), UserRole.INSTRUCTOR, request()))
			.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
		assertThat(classrooms.count()).isEqualTo(3);
	}

	@Test
	void aStaleInstructorRoleCannotCreateAfterTheDatabaseRoleWasRevoked() {
		Fixture fixture = fixture(AuthProvider.LOCAL);
		new TransactionTemplate(transactions).executeWithoutResult(transaction ->
			users.findById(fixture.ownerId()).orElseThrow().changeRole(UserRole.LEARNER));
		assertThatThrownBy(() -> classroomService.create(fixture.ownerId(), UserRole.INSTRUCTOR, request()))
			.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
		assertThat(classrooms.count()).isEqualTo(3);
	}

	@Test
	void mysqlConcurrentExistingClassroomEditAndWithdrawalDoNotInvertUserAndClassroomLocks() throws Exception {
		Assumptions.assumeTrue(System.getenv("CLASSROOM_WITHDRAWAL_MYSQL_URL") != null,
			"Real MySQL lock ordering is tested only against the disposable loopback database");
		Fixture fixture = fixture(AuthProvider.LOCAL);
		CountDownLatch ownerLocked = new CountDownLatch(1);
		CountDownLatch allowClosure = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var withdrawal = executor.submit(() -> {
				new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
					users.findByIdForUpdate(fixture.ownerId()).orElseThrow();
					ownerLocked.countDown();
					try { assertThat(allowClosure.await(15, TimeUnit.SECONDS)).isTrue(); }
					catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
					userService.withdraw(fixture.ownerId(), "StrongPass123!");
				});
				return null;
			});
			try {
				assertThat(ownerLocked.await(15, TimeUnit.SECONDS)).isTrue();
				var edit = executor.submit(() -> {
					new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
						Classroom room = classrooms.findByIdForUpdate(fixture.activeId()).orElseThrow();
						room.update("Edited before closure", null, null, null, false, null);
						classrooms.flush();
					});
					return null;
				});
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (!edit.isDone() && System.nanoTime() < deadline
					&& jdbc.queryForObject("select count(*) from information_schema.innodb_trx where trx_state='LOCK WAIT'", Integer.class) == 0) {
					Thread.sleep(20);
				}
				allowClosure.countDown();
				edit.get(15, TimeUnit.SECONDS);
				withdrawal.get(15, TimeUnit.SECONDS);
			} finally { allowClosure.countDown(); }
		}
		assertThat(users.findById(fixture.ownerId()).orElseThrow().getStatus()).isEqualTo(UserStatus.DELETED);
		Classroom persisted = classrooms.findById(fixture.activeId()).orElseThrow();
		assertThat(persisted.getStatus()).isEqualTo(ClassroomStatus.COMPLETED);
		assertThat(persisted.getName()).isEqualTo("Edited before closure");
	}

	private Fixture fixture(AuthProvider provider) {
		User owner = provider == AuthProvider.LOCAL
			? User.create("owner@example.com", passwords.encode("StrongPass123!"), "Synthetic owner", UserRole.INSTRUCTOR)
			: User.createGoogle("owner@example.com", "google-only", "Synthetic owner", UserRole.INSTRUCTOR,
				null, false, null, null, null, "synthetic-owner-sub");
		owner = users.saveAndFlush(owner);
		User other = users.saveAndFlush(User.create("other@example.com", "hash", "Other", UserRole.INSTRUCTOR));
		User learner = users.saveAndFlush(User.create("learner@example.com", "hash", "Learner"));
		Classroom active = classrooms.saveAndFlush(room(owner, "OWN-ACT1"));
		Classroom completed = room(owner, "OWN-DONE"); completed.complete(); completed = classrooms.saveAndFlush(completed);
		Classroom otherRoom = classrooms.saveAndFlush(room(other, "OTHER-A1"));
		members.saveAndFlush(ClassroomMember.create(active, learner, Instant.parse("2026-10-01T00:00:00Z")));
		return new Fixture(owner.getId(), active.getId(), completed.getId(), otherRoom.getId());
	}

	private void withdraw(Fixture fixture, AuthProvider provider) {
		if (provider == AuthProvider.LOCAL) { userService.withdraw(fixture.ownerId(), "StrongPass123!"); }
		else { userService.withdrawGoogle(fixture.ownerId(), "synthetic-owner-sub"); }
	}

	private Classroom room(User owner, String invite) {
		return Classroom.create(owner, "Synthetic classroom", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
			ClassroomColor.BLUE, null, invite);
	}

	private CreateClassroomRequest request() {
		return new CreateClassroomRequest("New synthetic classroom", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), ClassroomColor.BLUE, null);
	}

	private record Fixture(Long ownerId, Long activeId, Long completedId, Long otherId) { }
}
