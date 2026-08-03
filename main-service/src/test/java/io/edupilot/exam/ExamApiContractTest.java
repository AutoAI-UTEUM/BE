package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.edupilot.Epic10ServiceMocks;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.exam.dto.ExamAnswerResultResponse;
import io.edupilot.exam.dto.ExamOptionResponse;
import io.edupilot.exam.dto.ExamSubmissionResponse;
import io.edupilot.exam.dto.ExamSubmissionSummaryResponse;
import io.edupilot.exam.dto.StudentExamDetailResponse;
import io.edupilot.exam.dto.StudentExamListItemResponse;
import io.edupilot.exam.dto.StudentExamListResponse;
import io.edupilot.exam.dto.StudentExamQuestionResponse;
import io.edupilot.feedback.FeedbackRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest
@ActiveProfiles("test")
@Epic10ServiceMocks
class ExamApiContractTest {

	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
	private static final Set<String> PRIVATE_KEYS = Set.of(
		"answerChoiceId", "answerValue", "explanation", "referenceAnswer",
		"modelAnswer", "rubric", "privateAnswer", "isCorrect"
	);

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private InstructorExamService instructorExamService;
	@Autowired private StudentExamService studentExamService;
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@MockitoBean private UserRepository userRepository;
	@MockitoBean private RefreshTokenRepository refreshTokenRepository;
	@MockitoBean private LearningMaterialRepository materialRepository;
	@MockitoBean private MaterialPageRepository materialPageRepository;
	@MockitoBean private LearningSessionRepository sessionRepository;
	@MockitoBean private ChatMessageRepository messageRepository;
	@MockitoBean private NoteRepository noteRepository;
	@MockitoBean private FeedbackRepository feedbackRepository;
	@MockitoBean private QuizRepository quizRepository;
	@MockitoBean private QuizSubmissionRepository quizSubmissionRepository;

	private MockMvc mockMvc;
	private String instructorToken;
	private String learnerToken;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
		instructorToken = token(1L, UserRole.INSTRUCTOR);
		learnerToken = token(2L, UserRole.LEARNER);
	}

	@Test
	void subjectiveReturns202AndDeterministicReturns200WithSameBodySchema() throws Exception {
		ExamSubmissionResponse submitted = submission(10L, SubmissionStatus.SUBMITTED);
		ExamSubmissionResponse graded = submission(11L, SubmissionStatus.GRADED);
		when(studentExamService.submit(eq(2L), eq(UserRole.LEARNER), eq(30L), any()))
			.thenReturn(submitted, graded);

		MvcResult accepted = submit("async-request")
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"))
			.andReturn();
		MvcResult completed = submit("sync-request")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("GRADED"))
			.andReturn();

		assertThat(fieldNames(data(accepted))).isEqualTo(fieldNames(data(completed)));
		assertThat(fieldNames(data(accepted).get("items").get(0)))
			.isEqualTo(fieldNames(data(completed).get("items").get(0)));
	}

	@Test
	void sameRequestReturns202AndPollingUsesResponseStatus() throws Exception {
		ExamSubmissionResponse submitted = submission(10L, SubmissionStatus.SUBMITTED);
		when(studentExamService.submit(eq(2L), eq(UserRole.LEARNER), eq(30L), any()))
			.thenReturn(submitted);
		when(studentExamService.mySubmission(2L, UserRole.LEARNER, 30L, null))
			.thenReturn(submitted);

		submit("same-request").andExpect(status().isAccepted());
		submit("same-request").andExpect(status().isAccepted());
		mockMvc.perform(get("/api/exams/30/submissions/me")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.submissionId").value(10))
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"));

		verify(studentExamService, times(2))
			.submit(eq(2L), eq(UserRole.LEARNER), eq(30L), any());
	}

	@Test
	void studentListDetailAndSubmissionNeverExposePrivateAnswerKeys() throws Exception {
		ExamSubmissionSummaryResponse summary = new ExamSubmissionSummaryResponse(
			10L, 1, SubmissionStatus.GRADED,
			new BigDecimal("8.00"), new BigDecimal("10.00"), new BigDecimal("80.00")
		);
		when(studentExamService.list(2L, UserRole.LEARNER, 20L, 0, 20))
			.thenReturn(new StudentExamListResponse(List.of(
				new StudentExamListItemResponse(
					30L, "시험", 1, ExamStatus.PUBLISHED, true, true,
					new BigDecimal("10.00"), summary, NOW, null
				)
			), 0, 20, 1, 1));
		when(studentExamService.detail(2L, UserRole.LEARNER, 30L))
			.thenReturn(new StudentExamDetailResponse(
				30L, 20L, "시험", null, 1, ExamStatus.PUBLISHED, true, true,
				new BigDecimal("10.00"),
				List.of(new StudentExamQuestionResponse(
					"q1", "문항", new BigDecimal("10.00"), ExamQuestionType.MCQ,
					List.of(new ExamOptionResponse("a", "선택지"))
				)),
				summary, NOW, null
			));
		when(studentExamService.mySubmission(2L, UserRole.LEARNER, 30L, null))
			.thenReturn(submission(10L, SubmissionStatus.GRADED));

		MvcResult list = mockMvc.perform(get("/api/classrooms/20/exams")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken)))
			.andExpect(status().isOk()).andReturn();
		MvcResult detail = mockMvc.perform(get("/api/exams/30")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken)))
			.andExpect(status().isOk()).andReturn();
		MvcResult submission = mockMvc.perform(get("/api/exams/30/submissions/me")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken)))
			.andExpect(status().isOk()).andReturn();

		assertNoPrivateKeys(list);
		assertNoPrivateKeys(detail);
		assertNoPrivateKeys(submission);
	}

	@Test
	void draftIsHiddenAndClosedSubmissionIsRejected() throws Exception {
		doThrow(new BusinessException(ErrorCode.EXAM_NOT_FOUND))
			.when(studentExamService).detail(2L, UserRole.LEARNER, 41L);
		doThrow(new BusinessException(ErrorCode.EXAM_NOT_PUBLISHED))
			.when(studentExamService).submit(eq(2L), eq(UserRole.LEARNER), eq(42L), any());

		mockMvc.perform(get("/api/exams/41")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("EXAM_NOT_FOUND"));
		mockMvc.perform(post("/api/exams/42/submissions")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("closed-request")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EXAM_NOT_PUBLISHED"));
	}

	@Test
	void instructorStateTransitionErrorsMatchTargetState() throws Exception {
		doThrow(new BusinessException(ErrorCode.EXAM_NOT_PUBLISHED))
			.when(instructorExamService).close(1L, UserRole.INSTRUCTOR, 51L);
		doThrow(new BusinessException(ErrorCode.EXAM_NOT_EDITABLE))
			.when(instructorExamService).publish(1L, UserRole.INSTRUCTOR, 52L);

		mockMvc.perform(post("/api/exams/51/close")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EXAM_NOT_PUBLISHED"));
		mockMvc.perform(post("/api/exams/52/publish")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EXAM_NOT_EDITABLE"));
	}

	@Test
	void completedClassroomAllowsCloseAndDraftDeleteButBlocksLearningWrites() throws Exception {
		doThrow(new BusinessException(ErrorCode.CLASSROOM_COMPLETED))
			.when(instructorExamService).create(eq(1L), eq(UserRole.INSTRUCTOR), eq(20L), any());
		doThrow(new BusinessException(ErrorCode.CLASSROOM_COMPLETED))
			.when(instructorExamService).update(eq(1L), eq(UserRole.INSTRUCTOR), eq(61L), any());
		doThrow(new BusinessException(ErrorCode.CLASSROOM_COMPLETED))
			.when(instructorExamService).publish(1L, UserRole.INSTRUCTOR, 61L);
		doThrow(new BusinessException(ErrorCode.CLASSROOM_COMPLETED))
			.when(studentExamService).submit(eq(2L), eq(UserRole.LEARNER), eq(61L), any());

		mockMvc.perform(post("/api/exams/62/close")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isOk());
		mockMvc.perform(delete("/api/exams/63")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/classrooms/20/exams")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"시험\"}"))
			.andExpect(status().isConflict());
		mockMvc.perform(patch("/api/exams/61")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"수정\"}"))
			.andExpect(status().isConflict());
		mockMvc.perform(post("/api/exams/61/publish")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isConflict());
		mockMvc.perform(post("/api/exams/61/submissions")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("completed-request")))
			.andExpect(status().isConflict());
	}

	@Test
	void openApiDeclaresSameSubmissionSchemaFor200And202() throws Exception {
		MvcResult result = mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode responses = objectMapper.readTree(result.getResponse().getContentAsByteArray())
			.get("paths").get("/api/exams/{examId}/submissions").get("post").get("responses");

		assertThat(responses.has("200")).isTrue();
		assertThat(responses.has("202")).isTrue();
		assertThat(responses.get("200").get("content")).isNotNull();
		assertThat(responses.get("202").get("content"))
			.isEqualTo(responses.get("200").get("content"));
	}

	private org.springframework.test.web.servlet.ResultActions submit(String requestId)
		throws Exception {
		return mockMvc.perform(post("/api/exams/30/submissions")
			.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken))
			.contentType(MediaType.APPLICATION_JSON)
			.content(request(requestId)));
	}

	private String request(String requestId) {
		return "{\"requestId\":\"" + requestId
			+ "\",\"answers\":[{\"questionId\":\"q1\",\"answer\":\"답안\"}]}";
	}

	private ExamSubmissionResponse submission(Long id, SubmissionStatus status) {
		boolean submitted = status == SubmissionStatus.SUBMITTED;
		return new ExamSubmissionResponse(
			id, 1, status,
			submitted ? null : new BigDecimal("8.00"),
			new BigDecimal("10.00"),
			submitted ? null : new BigDecimal("80.00"),
			NOW,
			submitted ? null : NOW.plusSeconds(1),
			List.of(new ExamAnswerResultResponse(
				"q1", "답안", submitted ? null : new BigDecimal("8.00"),
				new BigDecimal("10.00"), submitted ? null : Verdict.PARTIAL,
				submitted ? null : "피드백"
			))
		);
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
	}

	private Set<String> fieldNames(JsonNode node) {
		Set<String> names = new HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private void assertNoPrivateKeys(MvcResult result) throws Exception {
		JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(findForbiddenKeys(root)).isEmpty();
	}

	private Set<String> findForbiddenKeys(JsonNode node) {
		Set<String> found = new HashSet<>();
		if (node.isObject()) {
			Iterator<String> names = node.fieldNames();
			while (names.hasNext()) {
				String name = names.next();
				if (PRIVATE_KEYS.contains(name)) {
					found.add(name);
				}
				found.addAll(findForbiddenKeys(node.get(name)));
			}
		} else if (node.isArray()) {
			node.forEach(child -> found.addAll(findForbiddenKeys(child)));
		}
		return found;
	}

	private String token(Long id, UserRole role) {
		User user = User.create(role.name().toLowerCase() + "@example.com", "hash", role.name(), role);
		ReflectionTestUtils.setField(user, "id", id);
		return jwtTokenProvider.createAccessToken(user);
	}

	private String bearer(String token) {
		return "Bearer " + token;
	}
}
