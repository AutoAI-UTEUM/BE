package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
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
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.SaveExamAttemptDraftRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.quiz.QuizOption;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.usernote.UserNoteRepository;
import io.edupilot.usernote.UserNoteService;
import io.edupilot.usernote.dto.CreateUserNoteRequest;
import io.edupilot.usernote.dto.PatchUserNoteRequest;

/** Committed local persistence checks; no real AI, server, account or MySQL access. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
	"spring.datasource.url=jdbc:h2:mem:persistence-acceptance-20261007;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"logging.level.root=WARN",
	"logging.level.org.springframework=WARN",
	"logging.level.org.hibernate=WARN",
	"logging.level.com.zaxxer.hikari=WARN",
	"edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://127.0.0.1:1",
	"edupilot.ai.internal-token=synthetic-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/persistence-acceptance-20261007"
})
@ActiveProfiles("jpa-context")
class PersistenceAcceptanceJpaTest {
	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ExamRepository exams;
	@Autowired private ExamQuestionRepository questions;
	@Autowired private ExamSubmissionRepository submissions;
	@Autowired private ExamAttemptDraftRepository drafts;
	@Autowired private ExamAttemptStartRepository starts;
	@Autowired private ExamAttemptDraftService draftService;
	@Autowired private StudentExamService studentService;
	@Autowired private ExamGradingWorker worker;
	@Autowired private UserNoteService noteService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private WebApplicationContext webContext;
	@Autowired private ObjectMapper mapper;
	@MockitoSpyBean private ExamAnswerRepository answers;
	@MockitoSpyBean private UserNoteRepository notes;
	@MockitoBean private AiClient aiClient;
	@MockitoBean private ExamGradingDispatcher dispatcher;
	@MockitoBean private ExamGradingRecoveryScheduler recoveryScheduler;
	@MockitoBean private Clock clock;

	private Instant now;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		// No outer test transaction: every assertion reads after the service commits or rolls back.
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertThat(jdbc.execute((ConnectionCallback<String>) connection ->
			connection.getMetaData().getURL()))
			.startsWith("jdbc:h2:mem:persistence-acceptance-20261007");
		// UserNote's migration-only UNIQUE is absent from Hibernate-generated DDL.
		// Install its H2 equivalent; this is not a MySQL/Flyway migration rehearsal.
		jdbc.execute("create unique index if not exists uk_user_notes_user_client "
			+ "on user_notes (user_id, client_id)");
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		when(clock.instant()).thenReturn(now);
		mvc = MockMvcBuilders.webAppContextSetup(webContext)
			.apply(springSecurity()).addFilters(traceIdFilter).build();
	}

	@Test
	void committedDraftRestoresExactContentWithIndependentTokenAndIsPrivate() throws Exception {
		Fixture fixture = fixture();
		String deviceA = bearer(fixture.learner());
		String deviceB = anotherBearer(fixture.learner());
		assertThat(deviceB).isNotEqualTo(deviceA);
		mvc.perform(get(draftPath(fixture)).header(HttpHeaders.AUTHORIZATION, deviceB))
			.andExpect(status().isNoContent())
			.andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEmpty());
		List<ExamAnswerRequest> content = List.of(
			new ExamAnswerRequest("q1", "a"),
			new ExamAnswerRequest("q2", "합성 답안\n줄바꿈, \"인용\", 😀")
		);
		mvc.perform(put(draftPath(fixture)).header(HttpHeaders.AUTHORIZATION, deviceA)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(new SaveExamAttemptDraftRequest(0, content))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
		JsonNode restored = json(mvc.perform(get(draftPath(fixture))
				.header(HttpHeaders.AUTHORIZATION, deviceB))
			.andExpect(status().isOk()).andReturn()).get("data");
		assertThat(restored.get("answers")).isEqualTo(mapper.valueToTree(content));
		assertThat(restored.get("savedAt").asText()).isEqualTo(now.toString());
		assertThat(draftService.get(fixture.learner().getId(), UserRole.LEARNER,
			fixture.exam().getId()).answers()).containsExactlyElementsOf(content);
		User other = user(UserRole.LEARNER);
		members.saveAndFlush(ClassroomMember.create(fixture.classroom(), other, now));
		mvc.perform(get(draftPath(fixture)).header(HttpHeaders.AUTHORIZATION, bearer(other)))
			.andExpect(status().isNoContent());
		assertThat(draftRows(fixture)).isOne();
	}

	@Test
	void staleDraftGetsLatestCommittedAnswerAndResolutionReplacesTheWholeSnapshot() throws Exception {
		Fixture fixture = fixture();
		String token = bearer(fixture.learner());
		draftService.save(fixture.learner().getId(), UserRole.LEARNER,
			fixture.exam().getId(), draft(0, "initial"));
		draftService.save(fixture.learner().getId(), UserRole.LEARNER,
			fixture.exam().getId(), draft(1, "winner"));
		mvc.perform(put(draftPath(fixture)).header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(draft(1, "loser"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DRAFT_VERSION_CONFLICT"))
			.andExpect(jsonPath("$.latestDraft.version").value(2))
			.andExpect(jsonPath("$.latestDraft.answers[0].answer").value("winner"));
		assertThat(draftService.get(fixture.learner().getId(), UserRole.LEARNER,
			fixture.exam().getId()).answers()).containsExactly(new ExamAnswerRequest("q2", "winner"));
		// Explicit resolution uses the server version; omitted q2 must not survive the replacement.
		var resolved = new SaveExamAttemptDraftRequest(2, List.of(new ExamAnswerRequest("q1", null)));
		mvc.perform(put(draftPath(fixture)).header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(resolved)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3));
		assertThat(draftService.get(fixture.learner().getId(), UserRole.LEARNER,
			fixture.exam().getId()).answers()).containsExactly(new ExamAnswerRequest("q1", null));
		assertThat(draftRows(fixture)).isOne();
	}

	@Test
	void concurrentFirstDraftCreatesReturnOneWinnerAndTheSameWinnerIn409() throws Exception {
		Fixture fixture = fixture();
		String token = bearer(fixture.learner());
		var results = race(
			() -> putDraft(fixture, token, draft(0, "device A")),
			() -> putDraft(fixture, token, draft(null, "device B"))
		);
		assertThat(results).extracting(result -> result.getResponse().getStatus())
			.containsExactlyInAnyOrder(200, 409);
		MvcResult conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409)
			.findFirst().orElseThrow();
		JsonNode latest = json(conflict).get("latestDraft");
		assertThat(json(conflict).get("error").get("code").asText()).isEqualTo("DRAFT_VERSION_CONFLICT");
		assertThat(latest.get("version").asInt()).isOne();
		String winner = latest.get("answers").get(0).get("answer").asText();
		assertThat(winner).isIn("device A", "device B");
		assertThat(draftService.get(fixture.learner().getId(), UserRole.LEARNER,
			fixture.exam().getId()).answers()).containsExactly(new ExamAnswerRequest("q2", winner));
		assertThat(draftRows(fixture)).isOne();
	}

	@Test
	void failedFinalWriteRollsBackDeletedDraftStartSubmissionAndAnswersThenRetryUsesBody() {
		Fixture fixture = fixture();
		Long userId = fixture.learner().getId();
		Long examId = fixture.exam().getId();
		draftService.save(userId, UserRole.LEARNER, examId, draft(0, "saved draft"));
		var started = starts.findByExam_IdAndUser_Id(examId, userId).orElseThrow().getStartedAt();
		AtomicBoolean failOnce = new AtomicBoolean(true);
		doAnswer(call -> {
			delegateRepositoryCall(call);
			if (failOnce.getAndSet(false)) { throw writeFailure(); }
			return null;
		}).when(answers).flush();
		SubmitExamRequest body = new SubmitExamRequest("persistence-final-retry", List.of(
			new ExamAnswerRequest("q1", "a"), new ExamAnswerRequest("q2", "submitted body")
		));
		assertThatThrownBy(() -> studentService.submit(userId, UserRole.LEARNER, examId, body))
			.isInstanceOf(DataAccessResourceFailureException.class);
		assertThat(submissions.countByExam_Id(examId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from exam_answers answer join exam_questions question "
			+ "on question.id = answer.question_id where question.exam_id = ?", Integer.class, examId)).isZero();
		assertThat(starts.findByExam_IdAndUser_Id(examId, userId).orElseThrow().getStartedAt())
			.isEqualTo(started);
		assertThat(draftService.get(userId, UserRole.LEARNER, examId).answers())
			.containsExactly(new ExamAnswerRequest("q2", "saved draft"));
		assertThat(draftRows(fixture)).isOne();
		when(clock.instant()).thenReturn(now.plusSeconds(5));
		var retried = studentService.submit(userId, UserRole.LEARNER, examId, body);
		assertThat(retried.status()).isEqualTo(SubmissionStatus.SUBMITTED);
		assertThat(submissions.countByExam_Id(examId)).isOne();
		assertThat(starts.findByExam_IdAndUser_Id(examId, userId)).isEmpty();
		assertThat(draftRows(fixture)).isZero();
		assertThat(answers.findBySubmission_IdOrderByQuestion_Id(retried.submissionId()))
			.extracting(ExamAnswer::getAnswer).containsExactly("a", "submitted body");
		assertThat(studentService.submit(userId, UserRole.LEARNER, examId,
			new SubmitExamRequest(body.requestId(), List.of(new ExamAnswerRequest("q2", "changed retry")))))
			.isEqualTo(retried);
		verify(dispatcher).dispatchAfterCommit(retried.submissionId(), examId);
	}

	@Test
	void failedNoteInsertRollsBackAndSameClientIdRetryCreatesOnceWithoutOverwriting() throws Exception {
		User owner = user(UserRole.LEARNER);
		String token = bearer(owner);
		String clientId = UUID.randomUUID().toString();
		CreateUserNoteRequest request = note(clientId, "first content");
		AtomicBoolean failOnce = new AtomicBoolean(true);
		doAnswer(call -> {
			Object saved = delegateRepositoryCall(call);
			if (failOnce.getAndSet(false)) { throw writeFailure(); }
			return saved;
		}).when(notes).saveAndFlush(any());
		assertThatThrownBy(() -> noteService.create(owner.getId(), request, null))
			.isInstanceOf(DataAccessResourceFailureException.class);
		assertThat(notes.findByUser_IdAndClientId(owner.getId(), clientId)).isEmpty();
		assertThat(noteRows(owner, clientId)).isZero();
		MvcResult retried = mvc.perform(post("/api/user-notes").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
			.andExpect(status().isCreated()).andReturn();
		long id = json(retried).get("data").get("id").asLong();
		mvc.perform(post("/api/user-notes").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(note(clientId, "changed retry"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id))
			.andExpect(jsonPath("$.data.content").value("first content"));
		assertThat(noteRows(owner, clientId)).isOne();
	}

	@Test
	void concurrentNoteClientIdRetriesKeepOneWholeContentAndAreScopedToTheOwner() throws Exception {
		User owner = user(UserRole.LEARNER);
		String clientId = UUID.randomUUID().toString();
		var results = race(
			() -> noteService.create(owner.getId(), note(clientId, "device A"), null),
			() -> noteService.create(owner.getId(), note(clientId, "device B"), null)
		);
		assertThat(results).filteredOn(result -> result.created()).hasSize(1);
		assertThat(results.get(0).data()).isEqualTo(results.get(1).data());
		assertThat(results.get(0).data().content()).isIn("device A", "device B");
		assertThat(noteRows(owner, clientId)).isOne();
		User other = user(UserRole.LEARNER);
		var otherNote = noteService.create(other.getId(), note(clientId, "other owner"), null);
		assertThat(otherNote.created()).isTrue();
		assertThat(otherNote.data().id()).isNotEqualTo(results.get(0).data().id());
		assertThat(noteRows(other, clientId)).isOne();
	}

	@Test
	void failedNotePatchRollsBackAndSuccessfulPatchRestoresWithIndependentToken() throws Exception {
		User owner = user(UserRole.LEARNER);
		long id = noteService.create(owner.getId(), note(UUID.randomUUID().toString(), "original"), null)
			.data().id();
		AtomicBoolean failOnce = new AtomicBoolean(true);
		doAnswer(call -> {
			delegateRepositoryCall(call);
			if (failOnce.getAndSet(false)) { throw writeFailure(); }
			return null;
		}).when(notes).flush();
		PatchUserNoteRequest rejected = new PatchUserNoteRequest();
		rejected.setContent("uncommitted content");
		assertThatThrownBy(() -> noteService.update(owner.getId(), id, rejected))
			.isInstanceOf(DataAccessResourceFailureException.class);
		assertThat(noteService.detail(owner.getId(), id).content()).isEqualTo("original");
		assertThat(noteService.detail(owner.getId(), id).updatedAt()).isEqualTo(now);
		String deviceA = bearer(owner);
		String deviceB = anotherBearer(owner);
		assertThat(deviceB).isNotEqualTo(deviceA);
		when(clock.instant()).thenReturn(now.plusSeconds(60));
		String updated = "수정된 합성 내용\n😀";
		mvc.perform(patch("/api/user-notes/" + id).header(HttpHeaders.AUTHORIZATION, deviceA)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(java.util.Map.of("content", updated))))
			.andExpect(status().isOk());
		mvc.perform(get("/api/user-notes/" + id).header(HttpHeaders.AUTHORIZATION, deviceB))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value(updated))
			.andExpect(jsonPath("$.data.updatedAt").value(now.plusSeconds(60).toString()));
		mvc.perform(get("/api/user-notes").header(HttpHeaders.AUTHORIZATION, deviceB))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id))
			.andExpect(jsonPath("$.data.items[0].content").value(updated));
		mvc.perform(get("/api/user-notes/" + id)
				.header(HttpHeaders.AUTHORIZATION, bearer(user(UserRole.LEARNER))))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOTE_NOT_FOUND"));
	}

	@Test
	void bodylessRegradeOnAnotherTokenPreservesStoredAnswerIdsRequestAndAttemptTiming() throws Exception {
		Fixture fixture = fixture();
		String deviceA = bearer(fixture.learner());
		String deviceB = anotherBearer(fixture.learner());
		Long userId = fixture.learner().getId();
		Long examId = fixture.exam().getId();
		draftService.save(userId, UserRole.LEARNER, examId, draft(0, "old draft"));
		when(clock.instant()).thenReturn(now.plusSeconds(7));
		String originalAnswer = "원래 제출 답안\n😀";
		var body = new SubmitExamRequest("persistence-regrade-fixed", List.of(
			new ExamAnswerRequest("q1", "a"), new ExamAnswerRequest("q2", originalAnswer)
		));
		MvcResult submitted = mvc.perform(post("/api/exams/" + examId + "/submissions")
				.header(HttpHeaders.AUTHORIZATION, deviceA).contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(body)))
			.andExpect(status().isAccepted()).andReturn();
		long submissionId = json(submitted).get("data").get("submissionId").asLong();
		var identity = submissionIdentity(submissionId);
		var storedAnswers = answerIdentity(submissionId);
		assertThat(draftRows(fixture)).isZero();
		when(aiClient.grade(any())).thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));
		worker.grade(submissionId);
		assertThat(submissions.findById(submissionId).orElseThrow().getStatus())
			.isEqualTo(SubmissionStatus.GRADING_FAILED);
		assertThat(submissionIdentity(submissionId)).isEqualTo(identity);
		assertThat(answerIdentity(submissionId)).isEqualTo(storedAnswers);
		clearInvocations(dispatcher, aiClient);
		for (int retry = 0; retry < 2; retry++) {
			mvc.perform(post("/api/exams/" + examId + "/submissions/me/regrade")
					.header(HttpHeaders.AUTHORIZATION, deviceB))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.data.submissionId").value(submissionId))
				.andExpect(jsonPath("$.data.attemptNo").value(1));
		}
		verify(dispatcher).dispatchAfterCommit(submissionId, examId);
		doAnswer(call -> {
			GradeRequest request = call.getArgument(0);
			assertThat(request.studentAnswers()).containsExactly(new GradeRequest.StudentAnswer("q2", originalAnswer));
			return new GradeResponse("1.0", examId, "SHORT", new BigDecimal("8.00"), BigDecimal.TEN,
				List.of(new GradeResponse.Item("q2", new BigDecimal("8.00"), BigDecimal.TEN, "PARTIAL", "Synthetic feedback")), null);
		}).when(aiClient).grade(any());
		worker.grade(submissionId);
		mvc.perform(get("/api/exams/" + examId + "/submissions/me").header(HttpHeaders.AUTHORIZATION, deviceB))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("GRADED"));
		mvc.perform(post("/api/exams/" + examId + "/submissions/me/regrade")
				.header(HttpHeaders.AUTHORIZATION, deviceB))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.submissionId").value(submissionId));
		assertThat(submissionIdentity(submissionId)).isEqualTo(identity);
		assertThat(answerIdentity(submissionId)).isEqualTo(storedAnswers);
		assertThat(submissions.countByExam_Id(examId)).isOne();
		assertThat(submissions.findById(submissionId).orElseThrow().getScore()).isEqualByComparingTo("18.00");
		verify(aiClient, times(1)).grade(any());
		verify(dispatcher, times(1)).dispatchAfterCommit(submissionId, examId);
	}

	private Fixture fixture() {
		User instructor = user(UserRole.INSTRUCTOR);
		User learner = user(UserRole.LEARNER);
		Classroom classroom = classrooms.saveAndFlush(Classroom.create(instructor, "Synthetic persistence",
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15), ClassroomColor.BLUE, null,
			UUID.randomUUID().toString().substring(0, 8)));
		members.saveAndFlush(ClassroomMember.create(classroom, learner, now));
		Exam exam = Exam.create(classroom, 1, "Synthetic acceptance", null, false);
		exam.replaceTotalScore(new BigDecimal("20.00"));
		exam.publish(now);
		exam = exams.saveAndFlush(exam);
		questions.saveAllAndFlush(List.of(
			ExamQuestion.create(exam, 1, ExamQuestionType.MCQ, BigDecimal.TEN,
				new ExamPublicQuestion("MCQ", List.of(new QuizOption("a", "A"), new QuizOption("b", "B"))),
				new ExamPrivateAnswer("a", null, "Synthetic explanation", null, null, List.of()), "1.0"),
			ExamQuestion.create(exam, 2, ExamQuestionType.SHORT, BigDecimal.TEN,
				new ExamPublicQuestion("SHORT", List.of()),
				new ExamPrivateAnswer(null, null, null, "Synthetic reference", null, List.of()), "1.0")
		));
		studentService.startAttempt(learner.getId(), UserRole.LEARNER, exam.getId());
		return new Fixture(learner, classroom, exam);
	}

	private User user(UserRole role) {
		return users.saveAndFlush(io.edupilot.VerifiedTestUsers.legacyVerified(User.create(
			"persistence-" + UUID.randomUUID() + "@example.com", "synthetic-hash", "Synthetic", role)));
	}

	private String bearer(User user) { return "Bearer " + tokens.createAccessToken(user); }

	private String anotherBearer(User user) {
		when(clock.instant()).thenReturn(now.plusSeconds(1));
		String token = bearer(user);
		when(clock.instant()).thenReturn(now);
		return token;
	}

	private String draftPath(Fixture fixture) { return "/api/exams/" + fixture.exam().getId() + "/attempts/draft"; }

	private SaveExamAttemptDraftRequest draft(Integer version, String content) {
		return new SaveExamAttemptDraftRequest(version, List.of(new ExamAnswerRequest("q2", content)));
	}

	private CreateUserNoteRequest note(String clientId, String content) {
		return new CreateUserNoteRequest(null, null, "Synthetic title", content, clientId);
	}

	private MvcResult putDraft(Fixture fixture, String token, SaveExamAttemptDraftRequest request) throws Exception {
		return mvc.perform(put(draftPath(fixture)).header(HttpHeaders.AUTHORIZATION, token)
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andReturn();
	}

	private JsonNode json(MvcResult result) throws Exception {
		return mapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private int draftRows(Fixture fixture) {
		return jdbc.queryForObject("select count(*) from exam_attempt_drafts where exam_id = ? and user_id = ?",
			Integer.class, fixture.exam().getId(), fixture.learner().getId());
	}

	private int noteRows(User owner, String clientId) {
		return jdbc.queryForObject("select count(*) from user_notes where user_id = ? and client_id = ?",
			Integer.class, owner.getId(), clientId);
	}

	private java.util.Map<String, Object> submissionIdentity(long submissionId) {
		return jdbc.queryForMap("select id, exam_id, user_id, attempt_no, request_id, submitted_at, started_at, "
			+ "duration_seconds from exam_submissions where id = ?", submissionId);
	}

	private List<java.util.Map<String, Object>> answerIdentity(long submissionId) {
		return jdbc.queryForList("select id, submission_id, question_id, answer from exam_answers "
			+ "where submission_id = ? order by question_id", submissionId);
	}

	private DataAccessResourceFailureException writeFailure() {
		return new DataAccessResourceFailureException("Synthetic failure after flush, before service commit");
	}

	private Object delegateRepositoryCall(InvocationOnMock call) throws Throwable {
		// Spring Data's spy is an interface proxy. Its default Answer delegates to the
		// real repository; callRealMethod() would try to invoke an abstract interface method.
		return mockingDetails(call.getMock()).getMockCreationSettings().getDefaultAnswer().answer(call);
	}

	private <T> List<T> race(Callable<T> left, Callable<T> right) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Callable<T> gatedLeft = gated(left, ready, start);
			Callable<T> gatedRight = gated(right, ready, start);
			var first = executor.submit(gatedLeft);
			var second = executor.submit(gatedRight);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}

	private <T> Callable<T> gated(Callable<T> task, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
			return task.call();
		};
	}

	private record Fixture(User learner, Classroom classroom, Exam exam) { }
}
