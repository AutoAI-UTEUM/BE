package io.edupilot.admin.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.global.config.ReadinessResponse;
import io.edupilot.global.config.ReadinessService;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:admin-infra;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/admin-infra",
		"edupilot.admin.infra.enabled=false"
	}
)
@ActiveProfiles("jpa-context")
class AdminInfraIntegrationTest {

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private UserRepository userRepository;
	@MockitoBean private ReadinessService readinessService;

	private MockMvc mockMvc;
	private User admin;
	private User learner;
	private User instructor;

	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		admin = saveUser("admin@example.com", UserRole.ADMIN);
		learner = saveUser("learner@example.com", UserRole.LEARNER);
		instructor = saveUser("instructor@example.com", UserRole.INSTRUCTOR);
		when(readinessService.check()).thenReturn(ReadinessResponse.of(true, true));
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
	}

	@Test
	void enforcesAdminAccessForEveryInfraEndpoint() throws Exception {
		List<String> endpoints = List.of(
			"/api/admin/infra/metrics",
			"/api/admin/infra/cost",
			"/api/admin/infra/app"
		);

		for (String endpoint : endpoints) {
			mockMvc.perform(get(endpoint))
				.andExpect(status().isUnauthorized());
			mockMvc.perform(get(endpoint)
					.header(HttpHeaders.AUTHORIZATION, bearer(learner)))
				.andExpect(status().isForbidden());
			mockMvc.perform(get(endpoint)
					.header(HttpHeaders.AUTHORIZATION, bearer(instructor)))
				.andExpect(status().isForbidden());
			mockMvc.perform(get(endpoint)
					.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk());
		}
	}

	@Test
	void startsWithoutAwsBeansAndReturnsDisabledWhileAppMetricsWork()
		throws Exception {
		assertThat(context.getBeansOfType(CloudWatchClient.class)).isEmpty();
		assertThat(context.getBeansOfType(CostExplorerClient.class)).isEmpty();

		mockMvc.perform(get("/api/admin/infra/metrics")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(false))
			.andExpect(jsonPath("$.data.reason").value("DISABLED"));
		mockMvc.perform(get("/api/admin/infra/cost")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(false))
			.andExpect(jsonPath("$.data.reason").value("DISABLED"));
		mockMvc.perform(get("/api/admin/infra/app")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true))
			.andExpect(jsonPath("$.data.jvm.heapUsedBytes").isNumber())
			.andExpect(jsonPath("$.data.jvm.liveThreads").isNumber())
			.andExpect(jsonPath("$.data.uptimeSeconds").isNumber())
			.andExpect(jsonPath("$.data.aiService.status").value("UP"));
	}

	@Test
	void doesNotExposeActuatorEndpoints() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isNotFound());
	}

	@Test
	void rejectsUnsupportedEnvironmentAndRange() throws Exception {
		mockMvc.perform(get("/api/admin/infra/metrics")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("env", "stage"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		mockMvc.perform(get("/api/admin/infra/metrics")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("range", "2h"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	private User saveUser(String email, UserRole role) {
		return userRepository.saveAndFlush(User.create(
			email,
			"password-hash",
			role.name(),
			role
		));
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user);
	}
}
