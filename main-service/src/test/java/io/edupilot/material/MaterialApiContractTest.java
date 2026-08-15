package io.edupilot.material;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
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
import io.edupilot.material.dto.MaterialOverviewResponse;
import io.edupilot.material.dto.MaterialSummaryResponse;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

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

	@Autowired
	private MaterialOverviewService overviewService;

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
		MockPart title = new MockPart(
			"title",
			"자료.pdf".getBytes(StandardCharsets.UTF_8)
		);
		MockPart classroomId = new MockPart("classroomId", "12".getBytes());
		MockPart weekNumber = new MockPart("weekNumber", "1".getBytes());

		mockMvc.perform(multipart("/api/materials")
				.file(pdf)
				.part(title, classroomId, weekNumber))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

		when(materialService.upload(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(UserRole.LEARNER),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq("자료.pdf"),
			org.mockito.ArgumentMatchers.eq(12L),
			org.mockito.ArgumentMatchers.eq(1)
		)).thenReturn(new MaterialSummaryResponse(
			10L,
			"자료.pdf",
			null,
			MaterialProcessingStatus.PROCESSING,
			null,
			null,
			Instant.parse("2026-07-25T00:00:00Z")
		));

		mockMvc.perform(multipart("/api/materials")
				.file(pdf)
				.part(title, classroomId, weekNumber)
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
			null,
			null,
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
				null,
				null,
				Instant.parse("2026-07-25T00:00:00Z")
			)
		);

		mockMvc.perform(get("/api/materials")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].materialId").value(10))
			.andExpect(jsonPath("$.data.items[0].failureReason").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.items[0].traceId").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(1));

		mockMvc.perform(get("/api/materials/10")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.processingStatus").value("READY"))
			.andExpect(jsonPath("$.data.learningAvailable").value(true))
			.andExpect(jsonPath("$.data.failureReason").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.traceId").value(
				org.hamcrest.Matchers.nullValue()
			));
	}

	@Test
	void overviewReturnsPendingEnvelopeAndHidesAccessFailure() throws Exception {
		when(overviewService.get(1L, 10L)).thenReturn(
			MaterialOverviewResponse.pending(10L)
		);

		mockMvc.perform(get("/api/materials/10/overview")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.materialId").value(10))
			.andExpect(jsonPath("$.data.content").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.status").value("PENDING"))
			.andExpect(jsonPath("$.data.updatedAt").value(
				org.hamcrest.Matchers.nullValue()
			));

		when(overviewService.get(1L, 99L)).thenThrow(
			new BusinessException(ErrorCode.MATERIAL_NOT_FOUND)
		);

		mockMvc.perform(get("/api/materials/99/overview")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("MATERIAL_NOT_FOUND"));
	}

	@Test
	void renameUsesDetailEnvelope() throws Exception {
		when(materialService.rename(1L, 10L, "수정된 제목")).thenReturn(
			new MaterialDetailResponse(
				10L,
				"수정된 제목",
				2,
				MaterialProcessingStatus.READY,
				true,
				null,
				null,
				Instant.parse("2026-07-25T00:00:00Z")
			)
		);

		mockMvc.perform(patch("/api/materials/10")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"수정된 제목\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.materialId").value(10))
			.andExpect(jsonPath("$.data.title").value("수정된 제목"))
			.andExpect(jsonPath("$.data.processingStatus").value("READY"))
			.andExpect(jsonPath("$.data.learningAvailable").value(true));
	}

	@Test
	void failedMaterialExposesStructuredReasonAndAllowsLegacyNulls()
		throws Exception {
		MaterialSummaryResponse failedItem = new MaterialSummaryResponse(
			10L,
			"failed",
			null,
			MaterialProcessingStatus.FAILED,
			MaterialFailureReason.EXTRACTION_FAILED,
			"upload-trace-10",
			Instant.parse("2026-07-25T00:00:00Z")
		);
		when(materialService.list(1L, 0, 20)).thenReturn(
			new MaterialListResponse(List.of(failedItem), 0, 20, 1, 1)
		);
		when(materialService.detail(1L, 10L)).thenReturn(
			new MaterialDetailResponse(
				10L,
				"failed",
				null,
				MaterialProcessingStatus.FAILED,
				false,
				MaterialFailureReason.EXTRACTION_FAILED,
				"upload-trace-10",
				Instant.parse("2026-07-25T00:00:00Z")
			)
		);
		when(materialService.detail(1L, 11L)).thenReturn(
			new MaterialDetailResponse(
				11L,
				"legacy failed",
				null,
				MaterialProcessingStatus.FAILED,
				false,
				null,
				null,
				Instant.parse("2026-07-24T00:00:00Z")
			)
		);

		mockMvc.perform(get("/api/materials")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].failureReason")
				.value("EXTRACTION_FAILED"))
			.andExpect(jsonPath("$.data.items[0].traceId")
				.value("upload-trace-10"));

		mockMvc.perform(get("/api/materials/10")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.failureReason")
				.value("EXTRACTION_FAILED"))
			.andExpect(jsonPath("$.data.traceId").value("upload-trace-10"));

		mockMvc.perform(get("/api/materials/11")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.processingStatus").value("FAILED"))
			.andExpect(jsonPath("$.data.failureReason").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.traceId").value(
				org.hamcrest.Matchers.nullValue()
			));
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
