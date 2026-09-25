package io.edupilot.usernote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.quiz.QuizOption;
import io.edupilot.quiz.QuizQuestionResult;
import io.edupilot.quiz.QuizQuestionResultService;
import io.edupilot.quiz.QuizType;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.usernote.dto.CreateUserNoteRequest;
import io.edupilot.usernote.dto.CreateWrongAnswerNoteRequest;
import io.edupilot.usernote.dto.ImportNotesRequest;
import io.edupilot.usernote.dto.PatchUserNoteRequest;
import io.edupilot.usernote.dto.PatchWrongAnswerNoteRequest;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:user-notes;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/user-notes"
	}
)
@ActiveProfiles("jpa-context")
class UserNoteJpaTest {

	private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
	private static final AtomicInteger IDS = new AtomicInteger();

	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private UserNoteRepository userNotes;
	@Autowired private WrongAnswerNoteRepository wrongNotes;
	@Autowired private UserNoteService userNoteService;
	@Autowired private WrongAnswerNoteService wrongNoteService;
	@Autowired private NoteImportService importService;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private WebApplicationContext webContext;
	@MockitoBean private QuizQuestionResultService quizResultService;
	@MockitoBean private AiClient aiClient;
	@MockitoBean private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
	}

	@Test
	void createsListsUpdatesAndSoftDeletesOnlyOwnedNotes() {
		User owner = user();
		User other = user();
		LearningMaterial material = material(owner);
		var created = userNoteService.create(owner.getId(), new CreateUserNoteRequest(
			material.getId(), 2, "원래 제목", "내용", "client-1"
		), null);
		assertThat(created.created()).isTrue();
		assertThat(created.data().materialId()).isEqualTo(material.getId());
		assertThat(userNoteService.list(owner.getId(), material.getId(), 0, 50)
			.items()).hasSize(1);
		assertThat(userNoteService.list(other.getId(), null, 0, 50)
			.items()).isEmpty();
		assertError(() -> userNoteService.detail(other.getId(), created.data().id()),
			ErrorCode.NOTE_NOT_FOUND);
		PatchUserNoteRequest patch = new PatchUserNoteRequest();
		patch.setTitle("수정 제목");
		patch.setPageNumber(null);
		assertThat(userNoteService.update(owner.getId(), created.data().id(), patch)
			.title()).isEqualTo("수정 제목");
		assertThat(userNoteService.detail(owner.getId(), created.data().id())
			.pageNumber()).isNull();
		assertError(() -> userNoteService.update(other.getId(), created.data().id(), patch),
			ErrorCode.NOTE_NOT_FOUND);
		assertError(() -> userNoteService.delete(other.getId(), created.data().id()),
			ErrorCode.NOTE_NOT_FOUND);
		userNoteService.delete(owner.getId(), created.data().id());
		assertThat(userNoteService.list(owner.getId(), null, 0, 50).items()).isEmpty();
		assertThat(userNotes.findById(created.data().id()).orElseThrow().getDeletedAt())
			.isEqualTo(NOW);
		assertError(() -> userNoteService.detail(owner.getId(), created.data().id()),
			ErrorCode.NOTE_NOT_FOUND);
	}

	@Test
	void materialAccessClientIdAndContentLimitAreEnforced() {
		User owner = user();
		User other = user();
		LearningMaterial material = material(owner);
		var request = new CreateUserNoteRequest(material.getId(), 1, "제목", "내용", "same-id");
		var first = userNoteService.create(owner.getId(), request, null);
		var duplicate = userNoteService.create(owner.getId(), request, null);
		assertThat(duplicate.created()).isFalse();
		assertThat(duplicate.data().id()).isEqualTo(first.data().id());
		assertThat(userNotes.countByUser_IdAndDeletedAtIsNull(owner.getId())).isOne();
		assertError(() -> userNoteService.create(other.getId(), request, null),
			ErrorCode.MATERIAL_NOT_FOUND);
		assertError(() -> userNoteService.create(owner.getId(), new CreateUserNoteRequest(
			999_999L, null, "제목", "내용", null
		), null), ErrorCode.MATERIAL_NOT_FOUND);
		assertError(() -> userNoteService.create(owner.getId(), new CreateUserNoteRequest(
			null, null, "제목", "한".repeat(350_000), null
		), null), ErrorCode.NOTE_TOO_LARGE);
	}

	@Test
	void noteListPaginatesByMostRecentlyUpdated() {
		User owner = user();
		Long firstId = userNoteService.create(owner.getId(),
			new CreateUserNoteRequest(null, null, "첫 노트", "내용", null), null)
			.data().id();
		when(clock.instant()).thenReturn(NOW.plusSeconds(10));
		Long secondId = userNoteService.create(owner.getId(),
			new CreateUserNoteRequest(null, null, "둘째 노트", "내용", null), null)
			.data().id();
		assertThat(userNoteService.list(owner.getId(), null, 0, 1).items())
			.singleElement().satisfies(note -> assertThat(note.id()).isEqualTo(secondId));
		assertThat(userNoteService.list(owner.getId(), null, 1, 1).items())
			.singleElement().satisfies(note -> assertThat(note.id()).isEqualTo(firstId));
	}

	@Test
	void wrongAnswerSnapshotComesFromOwnedSubmissionAndDuplicateResultReturnsExisting() {
		User owner = user();
		User other = user();
		when(quizResultService.requireOwned(owner.getId(), 77L, "q1"))
			.thenReturn(new QuizQuestionResult(
				"q1", "질문", QuizType.MCQ,
				List.of(new QuizOption("a", "선택지")), "a", "b"
			));
		when(quizResultService.requireOwned(other.getId(), 77L, "q1"))
			.thenThrow(new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		var first = wrongNoteService.create(owner.getId(),
			new CreateWrongAnswerNoteRequest("77:q1", "복습", "wrong-1"));
		assertThat(first.data().questionSnapshot().correctAnswer()).isEqualTo("a");
		assertThat(first.data().questionSnapshot().submittedAnswer()).isEqualTo("b");
		assertThat(wrongNotes.findById(first.data().id()).orElseThrow()
			.getQuestionSnapshot().questionText()).isEqualTo("질문");
		var duplicate = wrongNoteService.create(owner.getId(),
			new CreateWrongAnswerNoteRequest("077:q1", "새 메모", "wrong-2"));
		assertThat(duplicate.created()).isFalse();
		assertThat(duplicate.data().id()).isEqualTo(first.data().id());
		assertThat(wrongNotes.count()).isEqualTo(1);
		assertError(() -> wrongNoteService.create(other.getId(),
			new CreateWrongAnswerNoteRequest("77:q1", null, null)),
			ErrorCode.QUIZ_NOT_FOUND);
		PatchWrongAnswerNoteRequest patch = new PatchWrongAnswerNoteRequest();
		patch.setMemo("다시 보기");
		assertThat(wrongNoteService.update(owner.getId(), first.data().id(), patch).memo())
			.isEqualTo("다시 보기");
		assertError(() -> wrongNoteService.update(other.getId(), first.data().id(), patch),
			ErrorCode.WRONG_ANSWER_NOTE_NOT_FOUND);
		wrongNoteService.delete(owner.getId(), first.data().id());
		assertThat(wrongNoteService.list(owner.getId(), 0, 50).items()).isEmpty();
	}

	@Test
	void importKeepsSuccessfulItemsWhenAnotherItemFailsAndRerunSkips() {
		User user = user();
		var request = new ImportNotesRequest(List.of(
			new ImportNotesRequest.UserNoteItem(
				"good", null, null, "제목", "내용", "2026-09-01T00:00:00Z"),
			new ImportNotesRequest.UserNoteItem(
				"bad", 999_999L, null, "제목", "내용", null)
		), List.of());
		var first = importService.importOnce(user.getId(), request);
		assertThat(first.imported()).isOne();
		assertThat(first.failed()).containsExactly(
			new io.edupilot.usernote.dto.ImportNotesResponse.FailedItem(
				"bad", "MATERIAL_NOT_FOUND")
		);
		assertThat(userNoteService.list(user.getId(), null, 0, 50).items()).hasSize(1);
		assertThat(userNoteService.list(user.getId(), null, 0, 50)
			.items().getFirst().createdAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
		var rerun = importService.importOnce(user.getId(), request);
		assertThat(rerun.imported()).isZero();
		assertThat(rerun.skipped()).isOne();
		assertThat(userNotes.countByUser_IdAndDeletedAtIsNull(user.getId())).isOne();
	}

	@Test
	void importRejectsOversizedBatchAndSixthRequest() {
		User user = user();
		var oversized = new ImportNotesRequest(
			java.util.Collections.nCopies(201, new ImportNotesRequest.UserNoteItem(
				"id", null, null, "제목", "내용", null)), List.of());
		assertError(() -> importService.importOnce(user.getId(), oversized),
			ErrorCode.VALIDATION_FAILED);
		var empty = new ImportNotesRequest(List.of(), List.of());
		for (int attempt = 0; attempt < 5; attempt++) {
			importService.importOnce(user.getId(), empty);
		}
		assertError(() -> importService.importOnce(user.getId(), empty),
			ErrorCode.RATE_LIMIT_EXCEEDED);
	}

	@Test
	void importsWrongAnswersIndependentlyAndNeverTrustsClientSnapshot() throws Exception {
		User user = user();
		when(quizResultService.requireOwned(user.getId(), 88L, "q2"))
			.thenReturn(new QuizQuestionResult(
				"q2", "서버 문항", QuizType.OX, null, "true", "false"
			));
		when(quizResultService.requireOwned(user.getId(), 999L, "q1"))
			.thenThrow(new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		var request = new ImportNotesRequest(List.of(), List.of(
			new ImportNotesRequest.WrongAnswerItem("good-wrong", "88:q2", "복습"),
			new ImportNotesRequest.WrongAnswerItem("bad-wrong", "999:q1", null)
		));
		var first = importService.importOnce(user.getId(), request);
		assertThat(first.imported()).isOne();
		assertThat(first.failed()).singleElement()
			.satisfies(failed -> assertThat(failed.reason()).isEqualTo("QUIZ_NOT_FOUND"));
		assertThat(importService.importOnce(user.getId(), request).skipped()).isOne();
		assertThat(wrongNoteService.list(user.getId(), 0, 50).items()).hasSize(1);

		MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
			.apply(springSecurity()).addFilters(traceIdFilter).build();
		when(clock.instant()).thenReturn(Instant.now());
		String bearer = "Bearer " + jwtTokenProvider.createAccessToken(user);
		mvc.perform(post("/api/wrong-answer-notes")
				.header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"quizResultRef":"88:q2","clientId":"another-id",
					 "questionSnapshot":{"questionText":"FORGED"}}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.questionSnapshot.questionText")
				.value("서버 문항"));
	}

	@Test
	void apiUsesAuthenticationCreatedDuplicateAndNoContentContracts() throws Exception {
		User user = user();
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
			.apply(springSecurity()).addFilters(traceIdFilter).build();
		when(clock.instant()).thenReturn(Instant.now());
		String bearer = "Bearer " + jwtTokenProvider.createAccessToken(user);
		String body = "{\"title\":\"제목\",\"content\":\"내용\",\"clientId\":\"api-1\"}";
		mvc.perform(post("/api/user-notes")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/user-notes").header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.title").value("제목"));
		mvc.perform(post("/api/user-notes").header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk());
		Long id = userNoteService.list(user.getId(), null, 0, 50).items().getFirst().id();
		mvc.perform(get("/api/user-notes/" + id)
				.header(HttpHeaders.AUTHORIZATION, bearer))
			.andExpect(status().isOk());
		mvc.perform(patch("/api/user-notes/" + id)
				.header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"수정\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").value("수정"));
		mvc.perform(delete("/api/user-notes/" + id)
				.header(HttpHeaders.AUTHORIZATION, bearer))
			.andExpect(status().isNoContent());
	}

	private User user() {
		int id = IDS.incrementAndGet();
		return users.saveAndFlush(User.create("note-" + id + "@test.com", "hash", "학습자"));
	}

	private LearningMaterial material(User owner) {
		LearningMaterial material = LearningMaterial.create(
			owner, "자료", "notes/" + IDS.incrementAndGet() + ".pdf");
		material.markReady(4);
		return materials.saveAndFlush(material);
	}

	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
				.isEqualTo(expected));
	}
}
