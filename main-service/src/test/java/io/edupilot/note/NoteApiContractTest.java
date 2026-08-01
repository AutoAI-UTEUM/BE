package io.edupilot.note;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.feedback.FeedbackRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.note.dto.NoteListResponse;
import io.edupilot.note.dto.NoteResponse;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
@io.edupilot.Epic10ServiceMocks
class NoteApiContractTest {

	private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private NoteService noteService;

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private LearningMaterialRepository materialRepository;

	@MockitoBean
	private MaterialPageRepository materialPageRepository;

	@MockitoBean
	private LearningSessionRepository sessionRepository;

	@MockitoBean
	private ChatMessageRepository messageRepository;

	@MockitoBean
	private NoteRepository noteRepository;

	@MockitoBean
	private FeedbackRepository feedbackRepository;

	@MockitoBean
	private QuizRepository quizRepository;

	@MockitoBean
	private QuizSubmissionRepository submissionRepository;

	private MockMvc mockMvc;
	private String accessToken;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
		User user = User.create("user@example.com", "hash", "학습자");
		ReflectionTestUtils.setField(user, "id", 1L);
		accessToken = jwtTokenProvider.createAccessToken(user);
	}

	@Test
	void createRequiresAuthenticationAndReturnsReferenceFields() throws Exception {
		String body = """
			{
			  "content": "핵심 개념",
			  "pageNumber": 3,
			  "sourceMessageId": 501
			}
			""";
		mockMvc.perform(post("/api/sessions/100/notes")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code")
				.value("AUTHENTICATION_REQUIRED"));

		when(noteService.create(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(100L),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(note(1000L, "핵심 개념", 3, 501L));

		mockMvc.perform(post("/api/sessions/100/notes")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.noteId").value(1000))
			.andExpect(jsonPath("$.data.sessionId").value(100))
			.andExpect(jsonPath("$.data.materialId").value(10))
			.andExpect(jsonPath("$.data.pageNumber").value(3))
			.andExpect(jsonPath("$.data.sourceMessageId").value(501));
	}

	@Test
	void createAndUpdateRejectBlankOrOversizedContent() throws Exception {
		mockMvc.perform(post("/api/sessions/100/notes")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		String oversized = "a".repeat(10001);
		mockMvc.perform(patch("/api/notes/1000")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"" + oversized + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void bothListRoutesUseSamePageContractAndDefaults() throws Exception {
		NoteListResponse response = new NoteListResponse(
			List.of(note(1000L, "내용", 3, null)),
			0,
			50,
			1,
			1
		);
		when(noteService.listByMaterial(1L, 10L, 0, 50))
			.thenReturn(response);
		when(noteService.listBySession(1L, 100L, 0, 50))
			.thenReturn(response);

		mockMvc.perform(get("/api/materials/10/notes")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].noteId").value(1000))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(50))
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.totalPages").value(1));

		mockMvc.perform(get("/api/sessions/100/notes")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").value(
				org.hamcrest.Matchers.notNullValue()
			))
			.andExpect(jsonPath("$.data.items[0].noteId").value(1000));

		mockMvc.perform(get("/api/materials/10/notes")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.param("size", "101"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void updateAndDeleteHideOtherUsersNotesAsNoteNotFound() throws Exception {
		doThrow(new BusinessException(ErrorCode.NOTE_NOT_FOUND))
			.when(noteService).update(
				org.mockito.ArgumentMatchers.eq(1L),
				org.mockito.ArgumentMatchers.eq(2000L),
				org.mockito.ArgumentMatchers.any()
			);
		doThrow(new BusinessException(ErrorCode.NOTE_NOT_FOUND))
			.when(noteService).delete(1L, 2000L);

		mockMvc.perform(patch("/api/notes/2000")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"수정\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOTE_NOT_FOUND"));

		mockMvc.perform(delete("/api/notes/2000")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOTE_NOT_FOUND"));
	}

	private NoteResponse note(
		Long noteId,
		String content,
		Integer pageNumber,
		Long sourceMessageId
	) {
		return new NoteResponse(
			noteId,
			100L,
			10L,
			content,
			pageNumber,
			sourceMessageId,
			NOW,
			NOW
		);
	}

	private String bearer() {
		return "Bearer " + accessToken;
	}
}
