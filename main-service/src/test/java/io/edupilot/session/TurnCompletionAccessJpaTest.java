package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.VerifiedTestUsers;
import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.aiusage.AiQuotaService;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.classroom.*;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.GlobalExceptionHandler;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.memory.LearnerMemoryPromotionService;
import io.edupilot.memory.LearnerMemoryCandidate;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:turn-completion-access;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/turn-completion-access",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging"
})
@ActiveProfiles("jpa-context")
class TurnCompletionAccessJpaTest {
	private static final String REQUEST_ID = "synthetic-completion";
	private static final String CONTENT = "Synthetic protected explanation";
	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ClassroomWeekRepository weeks;
	@Autowired private ClassroomWeekMaterialRepository links;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private MaterialAccessService materialAccess;
	@Autowired private SessionStreamAccessGuard access;
	@Autowired private SessionTurnService turns;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TurnClaimService claims;
	@Autowired private TurnPreparationService preparation;
	@Autowired private TurnPersistenceService persistence;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private java.time.Clock clock;
	@Autowired private LearnerMemoryCandidateRepository candidates;
	@MockitoBean private AiClient ai;
	@MockitoBean private AiUsageService usage;
	@MockitoBean private AiQuotaService quota;
	@MockitoBean private TurnSnapshotService snapshots;
	@MockitoBean private SessionStreamService streamFacade;
	@MockitoBean private ConversationSummaryDispatcher summaries;
	@MockitoBean private LearnerMemoryPromotionService memory;
	private SessionStreamService streams;
	private MockMvc mvc;
	private User learner;
	private LearningSession session;
	private LearningMaterial material;
	private Classroom classroom;
	private ClassroomMember member;
	private ClassroomWeekMaterial link;
	private RecordingEmitter emitter;
	private final AtomicReference<AiStreamCancellation> upstream = new AtomicReference<>();

	@DynamicPropertySource
	static void optionalIsolatedMysql(DynamicPropertyRegistry settings) {
		String url = System.getenv("TURN_COMPLETION_MYSQL_URL");
		if (url == null || url.isBlank()) return;
		if (!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/runtime_completion_synthetic(?:\\?.*)?$")) {
			throw new IllegalArgumentException("Completion tests require the disposable loopback database");
		}
		settings.add("spring.datasource.url", () -> url);
		settings.add("spring.datasource.username", () -> "root");
		settings.add("spring.datasource.password", () -> "");
		settings.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}

	@BeforeEach
	void setup() {
		jdbc.execute("create table if not exists session_page_records (id bigint auto_increment primary key, session_id bigint not null, page_number int not null, explained_at timestamp, created_at timestamp not null, updated_at timestamp not null, unique(session_id,page_number))");
		if (System.getenv("TURN_COMPLETION_MYSQL_URL") != null) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("runtime_completion_synthetic");
		}
		User instructor = user(UserRole.INSTRUCTOR);
		learner = user(UserRole.LEARNER);
		classroom = classrooms.saveAndFlush(Classroom.create(instructor, "Synthetic classroom",
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15), ClassroomColor.BLUE, null,
			UUID.randomUUID().toString().substring(0, 10)));
		member = members.saveAndFlush(ClassroomMember.create(classroom, learner, Instant.now()));
		ClassroomWeek week = weeks.saveAndFlush(ClassroomWeek.create(classroom, 1, "Synthetic week", null,
			ClassroomWeekStatus.PUBLISHED, 1));
		material = LearningMaterial.create(instructor, "Synthetic PDF", "materials/" + UUID.randomUUID() + ".pdf");
		material.markReady(1);
		material = materials.saveAndFlush(material);
		link = links.saveAndFlush(ClassroomWeekMaterial.create(week, material, Instant.now()));
		session = sessions.saveAndFlush(LearningSession.create(learner, material));
		// This JDBC-only table is outside Hibernate create-drop. MySQL can reuse a prior run's session ID.
		// Remove only stale synthetic records for this freshly created fixture before asserting zero writes.
		jdbc.update("delete from session_page_records where session_id=?", session.getId());
		emitter = new RecordingEmitter();
		streams = new SessionStreamService(sessions, materialAccess, access, () -> emitter);
		when(snapshots.build(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(new TurnSnapshot(
			Map.of("sessionId", session.getId()), Map.of(), material.getId(), false));
		when(streamFacade.beginTurn(any(), any(), any(), any())).thenAnswer(invocation ->
			streams.beginTurn(invocation.getArgument(0), invocation.getArgument(1),
				invocation.getArgument(2), invocation.getArgument(3)));
		doAnswer(invocation -> {
			streams.complete(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
			return null;
		}).when(streamFacade).complete(any(), any(), any());
		doAnswer(invocation -> {
			streams.fail(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(streamFacade).fail(any(), any());
		mvc = MockMvcBuilders.standaloneSetup(new SessionController(null, turns, null, streamFacade))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
				@Override public boolean supportsParameter(MethodParameter parameter) {
					return parameter.getParameterType() == AuthenticatedUser.class;
				}
				@Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
					NativeWebRequest request, WebDataBinderFactory binder) {
					return new AuthenticatedUser(learner.getId(), UserRole.LEARNER);
				}
			}).build();
	}

	@AfterEach
	void stopStreams() { if (streams != null) streams.shutdown(); }

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void completedAfterLastDeltaRejectsRevokedAccess(Revocation revocation) throws Exception {
		stubStream(revocation, false);
		assertDenied(revocation);
		assertNotPersisted();
		assertThat(emitter.deliveries.get()).isEqualTo(2); // ready and the authorized final delta
		assertThat(upstream.get().isCancelled()).isTrue();
		verify(usage).record(any(), any(), any(), org.mockito.ArgumentMatchers.eq(true), any(), any());
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void jsonCompletionAlsoRejectsRevokedAccess(Revocation revocation) throws Exception {
		when(ai.executeTurn(any(), any())).thenAnswer(invocation -> {
			revoke(revocation);
			return response(invocation.getArgument(0));
		});
		assertDenied(revocation);
		assertNotPersisted();
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void userCancelledPartialAlsoRejectsRevokedAccess(Revocation revocation) throws Exception {
		stubStream(revocation, true);
		assertDenied(revocation);
		assertNotPersisted();
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void revocationAfterCommitRejectsPostWithoutMakingCompletedRequestRetryable(Revocation revocation) throws Exception {
		stubStream(null, false);
		doAnswer(invocation -> {
			assertThat(aiMessageCount()).isEqualTo(1);
			revoke(revocation);
			streams.complete(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
			return null;
		}).when(streamFacade).complete(any(), any(), any());
		assertDenied(revocation);
		assertThat(aiMessageCount()).isEqualTo(1);
		assertThat(userMessageStatus()).isEqualTo("COMPLETED");
		assertReleased();
		restore(revocation);
		perform().andExpect(status().isConflict()).andExpect(jsonPath("error.code").value("TURN_ALREADY_PROCESSED"));
	}

	@Test
	void authorizedJsonCompletionPersistsAndReturnsProtectedResult() throws Exception {
		when(ai.executeTurn(any(), any())).thenAnswer(invocation -> response(invocation.getArgument(0)));
		perform().andExpect(status().isOk()).andExpect(jsonPath("data.messages[0].content").value(CONTENT));
		assertThat(aiMessageCount()).isEqualTo(1);
		assertThat(userMessageStatus()).isEqualTo("COMPLETED");
		assertReleased();
		verify(summaries).dispatchAfterCommit(session.getId());
	}

	@Test
	void authorizedStreamCompletionStillDeliversTerminalEvent() throws Exception {
		stubStream(null, false);
		perform().andExpect(status().isOk());
		assertThat(aiMessageCount()).isEqualTo(1);
		assertThat(emitter.deliveries.get()).isGreaterThan(2);
		assertReleased();
	}

	@Test
	void authorizedUserCancellationStillPersistsPartialContent() throws Exception {
		stubStream(null, true);
		perform().andExpect(status().isOk()).andExpect(jsonPath("data.messages[0].content").value(CONTENT));
		assertThat(aiMessageCount()).isEqualTo(1);
		assertThat(userMessageStatus()).isEqualTo("COMPLETED");
		assertReleased();
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void revocationJustAfterCommitPreventsStartingMemoryPromotion(Revocation revocation) throws Exception {
		stubJsonMemoryWrite();
		doAnswer(invocation -> {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override public void afterCommit() {
					try (var executor = Executors.newSingleThreadExecutor()) {
						try { executor.submit(() -> revoke(revocation)).get(10, TimeUnit.SECONDS); }
						catch (Exception failure) { throw new IllegalStateException(failure); }
					}
				}
			});
			return null;
		}).when(summaries).dispatchAfterCommit(session.getId());
		assertDenied(revocation);
		assertThat(aiMessageCount()).isEqualTo(1);
		assertThat(userMessageStatus()).isEqualTo("COMPLETED");
		verifyNoInteractions(memory);
		assertReleased();
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void revocationDuringPostprocessingAlsoSuppressesJsonResponse(Revocation revocation) throws Exception {
		stubJsonMemoryWrite();
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			revoke(revocation);
			return null;
		}).when(memory).promoteMemory(any(), any(), any());
		assertDenied(revocation);
		assertThat(aiMessageCount()).isEqualTo(1);
		assertThat(userMessageStatus()).isEqualTo("COMPLETED");
		verify(memory).promoteMemory(any(), any(), any());
		assertReleased();
	}

	private void stubJsonMemoryWrite() {
		LearnerMemoryCandidate candidate = candidates.saveAndFlush(LearnerMemoryCandidate.create(learner, material,
			"CONCEPT", "Synthetic existing candidate", new java.math.BigDecimal("0.8"), List.of(), "1.0"));
		when(ai.executeTurn(any(), any())).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest request = invocation.getArgument(0);
			var base = completedResponse(request.turnId());
			return new io.edupilot.ai.dto.TurnResponse(base.schemaVersion(), base.turnId(), base.turnGoal(),
				base.actionsExecuted(), base.messages(), base.statePatch(), base.uiActions(), base.quiz(),
				base.memoryCandidates(), Map.of("candidateIds", List.of(candidate.getId())), null);
		});
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void savingTransactionOrdersRevocationAfterCommit(Revocation revocation) throws Exception {
		PreparedTurn prepared = prepare();
		CountDownLatch saved = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		CountDownLatch revoking = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var save = executor.submit(() -> new TransactionTemplate(transactions).execute(transaction -> {
				var result = persist(prepared);
				saved.countDown();
				await(commit);
				return result;
			}));
			await(saved);
			var removal = executor.submit(() -> { revoking.countDown(); revoke(revocation); });
			try {
				await(revoking);
				org.assertj.core.api.Assertions.assertThatThrownBy(() -> removal.get(300, TimeUnit.MILLISECONDS))
					.isInstanceOf(TimeoutException.class);
			} finally { commit.countDown(); }
			save.get(10, TimeUnit.SECONDS);
			removal.get(10, TimeUnit.SECONDS);
		}
		assertThat(aiMessageCount()).isEqualTo(1);
		assertThat(userMessageStatus()).isEqualTo("COMPLETED");
		claims.release(session.getId(), REQUEST_ID);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> access.assertAccessible(learner.getId(), session.getId(), UserRole.LEARNER))
			.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(code(revocation)));
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void revocationTransactionWinningRaceRejectsWaitingSave(Revocation revocation) throws Exception {
		PreparedTurn prepared = prepare();
		CountDownLatch revoked = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		CountDownLatch saving = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var removal = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
				revoke(revocation);
				revoked.countDown();
				await(commit);
			}));
			await(revoked);
			var save = executor.submit(() -> { saving.countDown(); return persist(prepared); });
			try {
				await(saving);
				org.assertj.core.api.Assertions.assertThatThrownBy(() -> save.get(300, TimeUnit.MILLISECONDS))
					.isInstanceOf(TimeoutException.class);
			} finally { commit.countDown(); }
			removal.get(10, TimeUnit.SECONDS);
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> save.get(10, TimeUnit.SECONDS))
				.hasCauseInstanceOf(BusinessException.class)
				.satisfies(error -> assertThat(((BusinessException) error.getCause()).errorCode()).isEqualTo(code(revocation)));
		}
		assertThat(aiMessageCount()).isZero();
		verifyNoInteractions(summaries, memory);
		claims.release(session.getId(), REQUEST_ID);
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void olderCallerAccountAndGrantSnapshotCannotAuthorizePersistence(Revocation revocation) {
		PreparedTurn prepared = prepare();
		TransactionTemplate older = new TransactionTemplate(transactions);
		// H2's RR locking read aborts on a changed snapshot; MySQL must prove the actual RR/current-read contract.
		older.setIsolationLevel(System.getenv("TURN_COMPLETION_MYSQL_URL") == null
			? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ);
		try (var executor = Executors.newSingleThreadExecutor()) {
			older.executeWithoutResult(transaction -> {
				assertThat(users.findById(learner.getId()).orElseThrow().isActive()).isTrue();
				materialAccess.assertSessionAccessible(learner.getId(), session.getId());
				try { executor.submit(() -> revoke(revocation)).get(10, TimeUnit.SECONDS); }
				catch (Exception failure) { throw new IllegalStateException(failure); }
				org.assertj.core.api.Assertions.assertThatThrownBy(() -> persist(prepared))
					.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(code(revocation)));
				transaction.setRollbackOnly();
			});
		}
		assertThat(aiMessageCount()).isZero();
		claims.release(session.getId(), REQUEST_ID);
	}

	@Test
	void anotherSurvivingGrantStillAllowsCompletion() throws Exception {
		User instructor = user(UserRole.INSTRUCTOR);
		Classroom other = classrooms.saveAndFlush(Classroom.create(instructor, "Another synthetic classroom",
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15), ClassroomColor.BLUE, null,
			UUID.randomUUID().toString().substring(0, 10)));
		members.saveAndFlush(ClassroomMember.create(other, learner, Instant.now()));
		ClassroomWeek week = weeks.saveAndFlush(ClassroomWeek.create(other, 1, "Synthetic draft week", null,
			ClassroomWeekStatus.PRIVATE, 1));
		links.saveAndFlush(ClassroomWeekMaterial.create(week, material, Instant.now()));
		when(ai.executeTurn(any(), any())).thenAnswer(invocation -> {
			revoke(Revocation.MEMBERSHIP_REMOVED);
			return response(invocation.getArgument(0));
		});
		perform().andExpect(status().isOk());
		assertThat(aiMessageCount()).isEqualTo(1);
		assertReleased();
	}

	@Test
	void materialOwnerCanPersistWithoutAClassroomGrant() {
		LearningMaterial owned = LearningMaterial.create(learner, "Synthetic owned PDF", "materials/" + UUID.randomUUID() + ".pdf");
		owned.markReady(1);
		material = materials.saveAndFlush(owned);
		session = sessions.saveAndFlush(LearningSession.create(learner, material));
		persist(prepare());
		assertThat(aiMessageCount()).isEqualTo(1);
		claims.release(session.getId(), REQUEST_ID);
	}

	@Test
	void newAccountOutsideGuardianYearCohortCanPersistWithoutLegacyOrGuardianApproval() {
		int birthYear=io.edupilot.guardian.BirthdatePolicy.today(clock).getYear()-15;
		jdbc.update("update users set access_cohort='NEW_SIGNUP',date_of_birth=?,age_verification_state='UNKNOWN' where id=?",
			java.sql.Date.valueOf(LocalDate.of(birthYear,12,31)),learner.getId());
		persist(prepare());
		assertThat(aiMessageCount()).isEqualTo(1);
		User current=users.findById(learner.getId()).orElseThrow();
		assertThat(current.getAccessCohort()).isEqualTo(io.edupilot.user.AccountAccessCohort.NEW_SIGNUP);
		assertThat(current.getAgeVerificationState()).isEqualTo(io.edupilot.guardian.AgeVerificationState.UNKNOWN);
		claims.release(session.getId(), REQUEST_ID);
	}

	private PreparedTurn prepare() {
		claims.claim(learner.getId(), session.getId(), REQUEST_ID);
		return preparation.prepare(learner.getId(), session.getId(), REQUEST_ID, "Synthetic request", null);
	}

	private PersistedTurn persist(PreparedTurn prepared) {
		return persistence.persist(learner.getId(), UserRole.LEARNER, session.getId(), REQUEST_ID,
			TurnEventType.EXPLAIN_CURRENT_PAGE, null, prepared.userMessageId(), false, completedResponse("synthetic-turn"));
	}

	private static void await(CountDownLatch latch) {
		try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
		catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
	}

	private void stubStream(Revocation revocation, boolean userCancelled) {
		streams.connect(learner.getId(), session.getId());
		when(ai.executeTurnStream(any(), any(), any(), any())).thenAnswer(invocation -> {
			upstream.set(invocation.getArgument(2));
			Consumer<TurnStreamEvent> listener = invocation.getArgument(1);
			listener.accept(TurnStreamEvent.contentDelta(CONTENT));
			if (revocation != null) revoke(revocation);
			if (userCancelled) {
				AiStreamCancellation cancellation = invocation.getArgument(2);
				cancellation.cancelByUser();
				throw new AiClientException(ErrorCode.AI_STREAM_INTERRUPTED, false, null);
			}
			return response(invocation.getArgument(0)); // completed does not call the listener
		});
	}

	private io.edupilot.ai.dto.TurnResponse response(io.edupilot.ai.dto.TurnRequest request) {
		return completedResponse(request.turnId());
	}

	private io.edupilot.ai.dto.TurnResponse completedResponse(String turnId) {
		return new io.edupilot.ai.dto.TurnResponse("1.0", turnId, "EXPLAIN", List.of(),
			List.of(Map.of("messageType", "EXPLANATION", "content", CONTENT)),
			Map.of("pageStatus", "EXPLAINED"), List.of(), null, List.of(), null, null);
	}

	private org.springframework.test.web.servlet.ResultActions perform() throws Exception {
		return mvc.perform(post("/api/sessions/{sessionId}/turns", session.getId())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"requestId\":\"" + REQUEST_ID + "\",\"eventType\":\"EXPLAIN_CURRENT_PAGE\",\"payload\":{}}"));
	}

	private void assertDenied(Revocation revocation) throws Exception {
		ErrorCode expected = code(revocation);
		perform().andExpect(status().is(expected.status().value()))
			.andExpect(jsonPath("error.code").value(expected.name()));
	}

	private ErrorCode code(Revocation revocation) {
		return switch (revocation) {
			case MEMBERSHIP_REMOVED, LINK_REMOVED -> ErrorCode.MATERIAL_NOT_FOUND;
			case ACCOUNT_SUSPENDED -> ErrorCode.ACCOUNT_SUSPENDED;
			case ROLE_CHANGED -> ErrorCode.TOKEN_INVALID;
			case AGE_UNKNOWN -> ErrorCode.AGE_VERIFICATION_REQUIRED;
			case GUARDIAN_PENDING -> ErrorCode.GUARDIAN_VERIFICATION_PENDING;
		};
	}

	private void assertNotPersisted() {
		assertThat(aiMessageCount()).isZero();
		assertThat(userMessageStatus()).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select count(*) from session_page_records where session_id=?", Long.class, session.getId())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from learner_memory_candidates where user_id=?", Long.class, learner.getId())).isZero();
		assertThat(sessions.findById(session.getId()).orElseThrow().getPageStatus()).isEqualTo(PageStatus.NOT_EXPLAINED);
		verifyNoInteractions(summaries, memory);
		assertReleased();
	}

	private long aiMessageCount() {
		return jdbc.queryForObject("select count(*) from chat_messages where session_id=? and sender_type='AI'", Long.class, session.getId());
	}

	private String userMessageStatus() {
		return jdbc.queryForObject("select status from chat_messages where session_id=? and request_id=?", String.class, session.getId(), REQUEST_ID);
	}

	private void assertReleased() {
		LearningSession current = sessions.findById(session.getId()).orElseThrow();
		assertThat(current.getStatus()).isEqualTo(SessionStatus.ACTIVE);
		assertThat(current.getActiveTurnRequestId()).isNull();
	}

	private User user(UserRole role) {
		return users.saveAndFlush(VerifiedTestUsers.legacyVerified(User.create(
			"synthetic-" + UUID.randomUUID() + "@example.test", "!synthetic", "Synthetic user", role)));
	}

	private void revoke(Revocation revocation) {
		switch (revocation) {
			case MEMBERSHIP_REMOVED -> { members.deleteById(member.getId()); members.flush(); }
			case LINK_REMOVED -> { links.deleteById(link.getId()); links.flush(); }
			case ACCOUNT_SUSPENDED -> jdbc.update("update users set status='SUSPENDED' where id=?", learner.getId());
			case ROLE_CHANGED -> jdbc.update("update users set role='INSTRUCTOR' where id=?", learner.getId());
			// Test-only state changes exercise late-result and locking boundaries, never approval.
			case AGE_UNKNOWN -> jdbc.update("update users set access_cohort='NEW_SIGNUP', age_verification_state='UNKNOWN' where id=?", learner.getId());
			case GUARDIAN_PENDING -> jdbc.update("update users set access_cohort='NEW_SIGNUP', age_verification_state='MANUAL_PENDING' where id=?", learner.getId());
		}
	}

	private void restore(Revocation revocation) {
		switch (revocation) {
			case MEMBERSHIP_REMOVED -> members.saveAndFlush(ClassroomMember.create(classroom, learner, Instant.now()));
			case LINK_REMOVED -> links.saveAndFlush(ClassroomWeekMaterial.create(link.getWeek(), material, Instant.now()));
			case ACCOUNT_SUSPENDED -> jdbc.update("update users set status='ACTIVE' where id=?", learner.getId());
			case ROLE_CHANGED -> jdbc.update("update users set role='LEARNER' where id=?", learner.getId());
			case AGE_UNKNOWN, GUARDIAN_PENDING -> jdbc.update("update users set access_cohort='LEGACY_EXEMPT', age_verification_state='UNKNOWN' where id=?", learner.getId());
		}
	}

	private enum Revocation { MEMBERSHIP_REMOVED, LINK_REMOVED, ACCOUNT_SUSPENDED, ROLE_CHANGED, AGE_UNKNOWN, GUARDIAN_PENDING }
	private static final class RecordingEmitter extends SseEmitter {
		private final AtomicInteger deliveries = new AtomicInteger();
		@Override public void send(SseEventBuilder event) throws IOException { deliveries.incrementAndGet(); }
	}
}
