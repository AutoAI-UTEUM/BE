package io.edupilot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialOutlinePersistenceService;
import io.edupilot.material.MaterialOutlineTaskDispatcher;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:admin-overview-regeneration;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/admin-overview-regeneration"
	}
)
@ActiveProfiles("jpa-context")
class AdminMaterialOverviewIntegrationTest {

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private UserRepository userRepository;
	@Autowired private LearningMaterialRepository materialRepository;
	@Autowired private MaterialOverviewRepository overviewRepository;
	@Autowired private MaterialOutlinePersistenceService persistenceService;
	@MockitoBean private MaterialOutlineTaskDispatcher dispatcher;

	private MockMvc mockMvc;
	private User admin;
	private User instructor;
	private User learner;
	private Long materialId;

	@BeforeEach
	void setUp() {
		admin = user(UserRole.ADMIN);
		instructor = user(UserRole.INSTRUCTOR);
		learner = user(UserRole.LEARNER);
		LearningMaterial material = LearningMaterial.create(instructor,
			"개요 재생성 자료", "materials/admin-overview-" + System.nanoTime() + ".pdf");
		material.markReady(1);
		materialId = materialRepository.saveAndFlush(material).getId();
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
	}

	@Test
	void onlyAdminCanQueueRegenerationAndRequestIsAudited() throws Exception {
		String endpoint = endpoint();
		mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(learner)))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(instructor)))
			.andExpect(status().isForbidden());

		Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditInterceptor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.success").value(true));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		verify(dispatcher).submitManual(materialId);
		assertThat(overviewRepository.findByMaterial_Id(materialId).orElseThrow()
			.getGenerationFailureCount()).isZero();
		assertThat(appender.list).anySatisfy(event ->
			assertThat(event.getKeyValuePairs())
				.anySatisfy(pair -> {
					assertThat(pair.key).isEqualTo("action");
					assertThat(pair.value).isEqualTo(
						"MATERIAL_OVERVIEW_REGENERATION_REQUESTED");
				}));
	}

	@Test
	void manualRequestResetsThreeFailuresAndLimitsSameMaterialToOncePerMinute()
		throws Exception {
		for (int attempt = 0; attempt < 3; attempt++) {
			persistenceService.markFailed(materialId);
		}
		assertThat(overviewRepository.findByMaterial_Id(materialId).orElseThrow()
			.getGenerationFailureCount()).isEqualTo(3);

		mockMvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isAccepted());
		mockMvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"));
		assertThat(overviewRepository.findByMaterial_Id(materialId).orElseThrow()
			.getGenerationFailureCount()).isZero();
	}

	private User user(UserRole role) {
		return userRepository.saveAndFlush(User.create(
			"overview-" + role + "-" + System.nanoTime() + "@example.com",
			"hash", role.name(), role
		));
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user);
	}

	private String endpoint() {
		return "/api/admin/materials/" + materialId + "/overview/regenerate";
	}
}
