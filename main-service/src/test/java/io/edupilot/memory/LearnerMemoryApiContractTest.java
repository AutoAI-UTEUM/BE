package io.edupilot.memory;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.assessment.QuizAssessmentRepository;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.diagnosis.RepairResultRepository;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.memory.dto.LearnerMemoryResponse;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
class LearnerMemoryApiContractTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private LearnerMemoryService memoryService;

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
	private ChatMessageRepository chatMessageRepository;

	@MockitoBean
	private QuizRepository quizRepository;

	@MockitoBean
	private QuizSubmissionRepository submissionRepository;

	@MockitoBean
	private QuizAssessmentRepository assessmentRepository;

	@MockitoBean
	private DiagnosisRepository diagnosisRepository;

	@MockitoBean
	private RepairResultRepository repairResultRepository;

	@MockitoBean
	private LearnerMemoryRepository memoryRepository;

	@MockitoBean
	private LearnerMemoryCandidateRepository candidateRepository;

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
	void memoryRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/users/me/memory")
				.param("materialId", "10"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void returnsPublicSummaryAndOmitsInternalFields() throws Exception {
		when(memoryService.get(1L, 10L)).thenReturn(
			new LearnerMemoryResponse(
				10L,
				List.of("강점"),
				List.of("약점"),
				List.of("예시 선호"),
				List.of("MCQ"),
				"digest",
				Instant.parse("2026-07-26T10:00:00Z")
			)
		);

		mockMvc.perform(get("/api/users/me/memory")
				.header(
					HttpHeaders.AUTHORIZATION,
					"Bearer " + accessToken
				)
				.param("materialId", "10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.materialId").value(10))
			.andExpect(jsonPath("$.data.strengths[0]").value("강점"))
			.andExpect(content().string(not(
				containsString("misconceptions")
			)))
			.andExpect(content().string(not(
				containsString("targetDifficulty")
			)))
			.andExpect(content().string(not(
				containsString("nextCoachingGoals")
			)))
			.andExpect(content().string(not(
				containsString("confidence")
			)))
			.andExpect(content().string(not(
				containsString("evidenceRefs")
			)));
	}
}
