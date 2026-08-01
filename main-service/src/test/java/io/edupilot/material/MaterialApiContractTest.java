package io.edupilot.material;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
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
import io.edupilot.material.dto.MaterialDetailResponse;
import io.edupilot.material.dto.MaterialListResponse;
import io.edupilot.material.dto.MaterialSummaryResponse;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
@io.edupilot.Epic10ServiceMocks
class MaterialApiContractTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private MaterialService materialService;

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private LearningMaterialRepository learningMaterialRepository;

	@MockitoBean
	private MaterialPageRepository materialPageRepository;

	@MockitoBean
	private LearningSessionRepository learningSessionRepository;

	@MockitoBean
	private ChatMessageRepository chatMessageRepository;

	@MockitoBean
	private NoteRepository noteRepository;

	@MockitoBean
	private FeedbackRepository feedbackRepository;

	@MockitoBean
	private QuizRepository quizRepository;

	@MockitoBean
	private QuizSubmissionRepository quizSubmissionRepository;

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
	void uploadRequiresAuthenticationAndReturnsImmediateProcessingState()
		throws Exception {
		MockMultipartFile pdf = new MockMultipartFile(
			"file",
			"material.pdf",
			"application/pdf",
			"%PDF-test".getBytes()
		);
		MockMultipartFile title = new MockMultipartFile(
			"title",
			"",
			"text/plain",
			"자료".getBytes(StandardCharsets.UTF_8)
		);

		mockMvc.perform(multipart("/api/materials")
				.file(pdf)
				.file(title))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

		when(materialService.upload(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq("자료")
		)).thenReturn(new MaterialSummaryResponse(
			10L,
			"자료",
			null,
			MaterialProcessingStatus.PROCESSING,
			Instant.parse("2026-07-25T00:00:00Z")
		));

		mockMvc.perform(multipart("/api/materials")
				.file(pdf)
				.file(title)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.materialId").value(10))
			.andExpect(jsonPath("$.data.pageCount").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.processingStatus").value("PROCESSING"));
	}

	@Test
	void uploadRequiresTitlePart() throws Exception {
		MockMultipartFile pdf = new MockMultipartFile(
			"file",
			"material.pdf",
			"application/pdf",
			"%PDF-test".getBytes()
		);

		mockMvc.perform(multipart("/api/materials")
				.file(pdf)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void listAndDetailUseDocumentedEnvelope() throws Exception {
		MaterialSummaryResponse item = new MaterialSummaryResponse(
			10L,
			"자료",
			2,
			MaterialProcessingStatus.READY,
			Instant.parse("2026-07-25T00:00:00Z")
		);
		when(materialService.list(1L, 0, 20)).thenReturn(
			new MaterialListResponse(List.of(item), 0, 20, 1, 1)
		);
		when(materialService.detail(1L, 10L)).thenReturn(
			new MaterialDetailResponse(
				10L,
				"자료",
				2,
				MaterialProcessingStatus.READY,
				true,
				Instant.parse("2026-07-25T00:00:00Z")
			)
		);

		mockMvc.perform(get("/api/materials")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].materialId").value(10))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(1));

		mockMvc.perform(get("/api/materials/10")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.processingStatus").value("READY"))
			.andExpect(jsonPath("$.data.learningAvailable").value(true));
	}

	@Test
	void ownershipFailuresAreHiddenAsMaterialNotFound() throws Exception {
		when(materialService.detail(1L, 99L)).thenThrow(
			new BusinessException(ErrorCode.MATERIAL_NOT_FOUND)
		);

		mockMvc.perform(get("/api/materials/99")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("MATERIAL_NOT_FOUND"));
	}

	@Test
	void fileStreamsPdfAndDeletePreservesGuardConflict() throws Exception {
		ByteArrayResource resource = new ByteArrayResource("%PDF-test".getBytes());
		when(materialService.file(1L, 10L))
			.thenReturn(new MaterialFile(10L, resource));

		mockMvc.perform(get("/api/materials/10/file")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
			.andExpect(header().string(
				HttpHeaders.CONTENT_DISPOSITION,
				containsString("inline")
			))
			.andExpect(content().bytes("%PDF-test".getBytes()));

		doThrow(new BusinessException(ErrorCode.MATERIAL_HAS_ACTIVE_SESSION))
			.when(materialService).delete(1L, 10L);
		mockMvc.perform(delete("/api/materials/10")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code")
				.value("MATERIAL_HAS_ACTIVE_SESSION"));
	}

	@Test
	void pageTextEndpointIsNotRegisteredInTestProfile() throws Exception {
		mockMvc.perform(get("/api/materials/10/pages/1")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	private String bearer() {
		return "Bearer " + accessToken;
	}
}
