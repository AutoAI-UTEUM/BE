package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.edupilot.ai.AiClient;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.note.dto.CreateNoteRequest;
import io.edupilot.note.dto.UpdateNoteRequest;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.PageStatus;
import io.edupilot.user.AccountAccessCohort;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

/** Actual Spring/JPA/JWT contracts for the local NOTE04 101/1/1 fixture; no live services. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
	"spring.datasource.url=jdbc:h2:mem:note04-api-persistence;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"logging.level.root=WARN",
	"logging.level.org.springframework=ERROR",
	"logging.level.org.hibernate=WARN",
	"logging.level.com.zaxxer.hikari=WARN",
	"edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://127.0.0.1:1",
	"edupilot.ai.internal-token=synthetic-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/note04-api-persistence",
	"edupilot.policy.signup-consent-required=true",
	"edupilot.mail.enabled=false",
	"edupilot.mail.provider=logging",
	"edupilot.mail.outbox.dispatch.mode=PAUSED",
	"edupilot.guardian.team.enabled=false",
	"edupilot.deletion.enabled=false"
})
@ActiveProfiles("jpa-context")
class Note04ApiPersistenceTest {
	private static final Instant QUIZ_TIME = Instant.parse("2026-10-04T00:00:00Z");
	private static final String PRIVATE_EXPLANATION = "NOTE04_PRIVATE_EXPLANATION";

	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private MaterialPageRepository materialPages;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private ChatMessageRepository messages;
	@Autowired private QuizRepository quizzes;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private WebApplicationContext context;
	@Autowired private ObjectMapper mapper;
	@MockitoSpyBean private NoteRepository notes;
	@MockitoBean private AiClient ai;
	@MockitoBean private Clock clock;

	private Instant now;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		// No enclosing test transaction: requests observe committed writes and completed rollbacks.
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertThat(jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL()))
			.startsWith("jdbc:h2:mem:note04-api-persistence");
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		when(clock.instant()).thenReturn(now);
		mvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity()).addFilters(traceIdFilter).build();
	}

	@ParameterizedTest
	@ValueSource(ints = {100, 37})
	void traversesAll101TiedQuizzesWithoutGapsDuplicatesOrSentinels(int size) throws Exception {
		Fixture f = fixture();
		String firstDevice = bearer(f.a());
		String secondDevice = anotherBearer(f.a());
		assertThat(firstDevice.equals(secondDevice)).isFalse();
		List<Long> collected = new ArrayList<>();
		int totalPages = (101 + size - 1) / size;
		for (int page = 0; page < totalPages; page++) {
			JsonNode data = page(f.a1(), firstDevice, page, size);
			assertPage(data, page, size, 101, totalPages, page + 1 < totalPages);
			assertThat(data.path("quizzes").size()).isEqualTo(Math.min(size, 101 - page * size));
			// Retrying the same page and reopening with another JWT preserve IDs and metadata.
			assertThat(page(f.a1(), firstDevice, page, size)).isEqualTo(data);
			assertThat(page(f.a1(), secondDevice, page, size)).isEqualTo(data);
			for (JsonNode quiz : data.path("quizzes")) {
				collected.add(quiz.path("quizId").longValue());
				assertThat(quiz.path("submitted").booleanValue()).isFalse();
				assertThat(quiz.path("quizType").stringValue()).isEqualTo("OX");
				assertThat(quiz.path("questionCount").intValue()).isEqualTo(1);
				assertThat(quiz.path("createdAt").stringValue()).isEqualTo(QUIZ_TIME.toString());
			}
		}
		assertThat(collected).containsExactlyElementsOf(f.a1QuizIds().stream().sorted(Comparator.reverseOrder()).toList());
		assertThat(collected).doesNotHaveDuplicates().doesNotContain(f.a2QuizId(), f.b1QuizId());
		JsonNode exhausted = page(f.a1(), firstDevice, totalPages, size);
		assertPage(exhausted, totalPages, size, 101, totalPages, false);
		assertThat(exhausted.path("quizzes").isEmpty()).isTrue();
		if (size == 100) {
			JsonNode defaultPage = data(mvc.perform(get(quizPath(f.a1()))
				.header(HttpHeaders.AUTHORIZATION, firstDevice)).andExpect(status().isOk()).andReturn());
			assertThat(defaultPage).isEqualTo(page(f.a1(), firstDevice, 0, 100));
		}
		assertFixtureUnsubmitted(f);
		verifyNoInteractions(ai);
	}

	@Test
	void actual103FixtureKeepsOwnerSessionAndPrivateAnswerBoundaries() throws Exception {
		Fixture f = fixture();
		String a = bearer(f.a());
		String b = bearer(f.b());
		assertThat(ids(page(f.a2(), a, 0, 100))).containsExactly(f.a2QuizId());
		assertThat(ids(page(f.b1(), b, 0, 100))).containsExactly(f.b1QuizId());
		for (LearningSession aSession : List.of(f.a1(), f.a2())) {
			mvc.perform(get(quizPath(aSession)).header(HttpHeaders.AUTHORIZATION, b))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"))
				.andExpect(jsonPath("$.data").doesNotExist());
		}
		mvc.perform(get(quizPath(f.b1())).header(HttpHeaders.AUTHORIZATION, a))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"))
			.andExpect(jsonPath("$.data").doesNotExist());
		mvc.perform(get("/api/quizzes/" + f.b1QuizId()).header(HttpHeaders.AUTHORIZATION, a))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("QUIZ_NOT_FOUND"));
		String detail = mvc.perform(get("/api/quizzes/" + f.a1QuizIds().getFirst())
			.header(HttpHeaders.AUTHORIZATION, a)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(detail).doesNotContain(PRIVATE_EXPLANATION, "privateAnswer", "answerValue", "rubric");
		mvc.perform(get(quizPath(f.a1()))).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
		for (String query : List.of("page=-1", "size=0", "size=101")) {
			mvc.perform(get(quizPath(f.a1()) + "?" + query).header(HttpHeaders.AUTHORIZATION, a))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		assertFixtureUnsubmitted(f);
		verifyNoInteractions(ai);
	}

	@Test
	void noteSourceRetryKeepsCommittedEditAcrossDevicesAndRejectsOtherScopes() throws Exception {
		Fixture f = noteFixture();
		String a = bearer(f.a());
		String anotherDevice = anotherBearer(f.a());
		String b = bearer(f.b());
		JsonNode created = createNote(f.a1(), a, "Synthetic AI draft", f.a1Message());
		long noteId = created.path("noteId").longValue();
		String editedContent = "Synthetic edit\nline: \"quote\", \uD83D\uDE00";
		JsonNode edited = data(mvc.perform(patch("/api/notes/" + noteId)
			.header(HttpHeaders.AUTHORIZATION, anotherDevice).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(new UpdateNoteRequest(editedContent))))
			.andExpect(status().isOk()).andReturn());
		assertThat(createNote(f.a1(), a, "Retry original AI draft", f.a1Message())).isEqualTo(edited);
		assertThat(createNote(f.a1(), anotherDevice, "Retry on another device", f.a1Message())).isEqualTo(edited);
		assertThat(noteRows(f)).isEqualTo(1);
		JsonNode restored = data(mvc.perform(get(notePath(f.a1()))
			.header(HttpHeaders.AUTHORIZATION, anotherDevice)).andExpect(status().isOk()).andReturn());
		assertThat(restored.path("totalElements").longValue()).isEqualTo(1);
		assertThat(restored.path("items").get(0)).isEqualTo(edited);
		assertThat(data(mvc.perform(get(notePath(f.a2())).header(HttpHeaders.AUTHORIZATION, a))
			.andExpect(status().isOk()).andReturn()).path("totalElements").longValue()).isZero();
		mvc.perform(post(notePath(f.a1())).header(HttpHeaders.AUTHORIZATION, a)
			.contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(new CreateNoteRequest("Wrong source", 7, f.a2Message()))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		mvc.perform(post(notePath(f.a1())).header(HttpHeaders.AUTHORIZATION, b)
			.contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(new CreateNoteRequest("Other owner", 7, f.a1Message()))))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
		mvc.perform(get(notePath(f.a1())).header(HttpHeaders.AUTHORIZATION, b))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
		mvc.perform(patch("/api/notes/" + noteId).header(HttpHeaders.AUTHORIZATION, b)
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new UpdateNoteRequest("Other owner"))))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOTE_NOT_FOUND"));
		assertThat(noteRows(f)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select content from notes where id = ?", String.class, noteId)).isEqualTo(editedContent);
		assertFixtureUnsubmitted(f);
		verifyNoInteractions(ai);
	}

	@Test
	void noteFailureAfterInsertRollsBackBeforeRetryCommitsOnlyOneSource() throws Exception {
		Fixture f = noteFixture();
		String a = bearer(f.a());
		AtomicBoolean failFirstInsert = new AtomicBoolean(true);
		doAnswer(call -> {
			// Delegate to the real Spring Data interface proxy before injecting a failure.
			Object saved = mockingDetails(call.getMock()).getMockCreationSettings().getDefaultAnswer().answer(call);
			if (failFirstInsert.compareAndSet(true, false)) {
				assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
				assertThat(noteRows(f)).isEqualTo(1);
				throw new DataAccessResourceFailureException("NOTE04_SYNTHETIC_FAILURE_AFTER_INSERT");
			}
			return saved;
		}).when(notes).saveAndFlush(any());
		MvcResult failed = mvc.perform(post(notePath(f.a1())).header(HttpHeaders.AUTHORIZATION, a)
			.contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(new CreateNoteRequest("Synthetic retry", 7, f.a1Message()))))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.data").doesNotExist()).andReturn();
		assertThat(failed.getResponse().getContentAsString()).doesNotContain("NOTE04_SYNTHETIC_FAILURE_AFTER_INSERT", "select", "stackTrace");
		assertThat(failFirstInsert.get()).isFalse();
		assertThat(noteRows(f)).isZero();
		JsonNode retry = createNote(f.a1(), a, "Synthetic retry", f.a1Message());
		assertThat(createNote(f.a1(), anotherBearer(f.a()), "Another retry", f.a1Message())).isEqualTo(retry);
		assertThat(noteRows(f)).isEqualTo(1);
		assertFixtureUnsubmitted(f);
		verifyNoInteractions(ai);
	}

	private Fixture fixture() {
		User a = adult();
		User b = adult();
		LearningSession a1 = session(a, "A1");
		LearningSession a2 = session(a, "A2");
		LearningSession b1 = session(b, "B1");
		List<Long> a1Ids = new ArrayList<>();
		for (int index = 0; index < 101; index++) a1Ids.add(quiz(a1, "A1-" + index));
		Long a2Id = quiz(a2, "A2-SENTINEL");
		Long b1Id = quiz(b1, "B1-SENTINEL");
		// Hibernate owns created_at; this local-only fixture explicitly creates equal timestamp ties.
		for (Long id : a1Ids) {
			assertThat(jdbc.update("update quizzes set created_at = ? where id = ?", Timestamp.from(QUIZ_TIME), id)).isEqualTo(1);
		}
		assertThat(jdbc.queryForObject("select count(distinct created_at) from quizzes where session_id = ?",
			Integer.class, a1.getId())).isEqualTo(1);
		Fixture f = new Fixture(a, b, a1, a2, b1, List.copyOf(a1Ids), a2Id, b1Id, null, null);
		assertFixtureUnsubmitted(f);
		// Page-only fixture has no JPA descendants. Hibernate does not create the JDBC-only
		// page/assessment tables; their migration/preflight coverage remains in PR503 MySQL tests.
		for (String table : List.of("chat_messages", "qa_threads", "notes")) {
			assertThat(jdbc.queryForObject("select count(*) from " + table + " where session_id in (?, ?, ?)",
				Integer.class, a1.getId(), a2.getId(), b1.getId())).isZero();
		}
		return f;
	}

	private Fixture noteFixture() {
		Fixture f = fixture();
		// Notes extend the local test fixture; a used fixture no longer meets adapter cleanup preconditions.
		Long a1Message = messages.saveAndFlush(ChatMessage.ai(f.a1(), "Synthetic A1 note draft")).getId();
		Long a2Message = messages.saveAndFlush(ChatMessage.ai(f.a2(), "Synthetic A2 note draft")).getId();
		return new Fixture(f.a(), f.b(), f.a1(), f.a2(), f.b1(), f.a1QuizIds(), f.a2QuizId(), f.b1QuizId(), a1Message, a2Message);
	}

	private User adult() {
		User user = User.create("note04-" + UUID.randomUUID() + "@example.com", "synthetic-hash", "Synthetic NOTE04");
		user.recordSignupDateOfBirth(LocalDate.of(2000, 1, 1));
		user.verifyEmail(now);
		user = users.saveAndFlush(user);
		assertThat(user.getAccessCohort()).isEqualTo(AccountAccessCohort.NEW_SIGNUP);
		assertThat(user.isEmailVerified()).isTrue();
		assertThat(user.isLegacyAccessExempt()).isFalse();
		return user;
	}

	private LearningSession session(User user, String alias) {
		LearningMaterial material = LearningMaterial.create(user, "NOTE04 " + alias, "materials/note04-" + UUID.randomUUID() + ".pdf");
		material.markReady(7);
		material = materials.saveAndFlush(material);
		materialPages.saveAndFlush(MaterialPage.create(material, 7, "Synthetic NOTE04 page7"));
		LearningSession session = LearningSession.create(user, material);
		session.moveTo(7, PageStatus.NOT_EXPLAINED, List.of());
		return sessions.saveAndFlush(session);
	}

	private Long quiz(LearningSession session, String label) {
		return quizzes.saveAndFlush(Quiz.create(session, 7, "NOTE04 " + label, 1, 7, QuizType.OX,
			List.of(new PublicQuizQuestion("q1", "Synthetic OX " + label, BigDecimal.TEN, null)),
			List.of(new PrivateQuizQuestion("q1", null, true, PRIVATE_EXPLANATION, null, null, null, null)), "1.0")).getId();
	}

	private JsonNode page(LearningSession session, String bearer, int page, int size) throws Exception {
		return data(mvc.perform(get(quizPath(session)).param("page", Integer.toString(page)).param("size", Integer.toString(size))
			.header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()).andReturn());
	}

	private void assertPage(JsonNode data, int page, int size, long total, int pages, boolean hasNext) {
		assertThat(data.path("page").intValue()).isEqualTo(page);
		assertThat(data.path("size").intValue()).isEqualTo(size);
		assertThat(data.path("totalElements").longValue()).isEqualTo(total);
		assertThat(data.path("totalPages").intValue()).isEqualTo(pages);
		assertThat(data.path("hasNext").booleanValue()).isEqualTo(hasNext);
	}

	private List<Long> ids(JsonNode page) {
		List<Long> result = new ArrayList<>();
		for (JsonNode quiz : page.path("quizzes")) result.add(quiz.path("quizId").longValue());
		return result;
	}

	private JsonNode createNote(LearningSession session, String bearer, String content, Long source) throws Exception {
		return data(mvc.perform(post(notePath(session)).header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new CreateNoteRequest(content, 7, source))))
			.andExpect(status().isOk()).andReturn());
	}

	private JsonNode data(MvcResult result) throws Exception {
		return mapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
	}

	private String bearer(User user) { return "Bearer " + tokens.createAccessToken(user); }

	private String anotherBearer(User user) {
		when(clock.instant()).thenReturn(now.plusSeconds(1));
		String token = bearer(user);
		when(clock.instant()).thenReturn(now);
		return token;
	}

	private String quizPath(LearningSession session) { return "/api/sessions/" + session.getId() + "/quizzes"; }
	private String notePath(LearningSession session) { return "/api/sessions/" + session.getId() + "/notes"; }

	private int noteRows(Fixture f) {
		return jdbc.queryForObject("select count(*) from notes where user_id = ? and source_message_id = ?",
			Integer.class, f.a().getId(), f.a1Message());
	}

	private void assertFixtureUnsubmitted(Fixture f) {
		assertThat(jdbc.queryForObject("select count(*) from quizzes where session_id in (?, ?, ?)",
			Integer.class, f.a1().getId(), f.a2().getId(), f.b1().getId())).isEqualTo(103);
		assertThat(jdbc.queryForObject("select count(*) from quiz_submissions where quiz_id in "
			+ "(select id from quizzes where session_id in (?, ?, ?))", Integer.class,
			f.a1().getId(), f.a2().getId(), f.b1().getId())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from email_deliveries", Integer.class)).isZero();
	}

	private record Fixture(User a, User b, LearningSession a1, LearningSession a2, LearningSession b1,
		List<Long> a1QuizIds, Long a2QuizId, Long b1QuizId, Long a1Message, Long a2Message) { }
}
