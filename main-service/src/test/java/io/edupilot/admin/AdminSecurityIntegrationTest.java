package io.edupilot.admin;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:admin-security;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/admin-security"
	}
)
@ActiveProfiles("jpa-context")
@Import(AdminSecurityIntegrationTest.AdminTestConfiguration.class)
class AdminSecurityIntegrationTest {

	private static final String ADMIN_ENDPOINT = "/api/admin/test-ping";

	@Autowired
	private WebApplicationContext context;
	@Autowired
	private TraceIdFilter traceIdFilter;
	@Autowired
	private JwtTokenProvider jwtTokenProvider;
	@Autowired
	private UserRepository userRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
	}

	@Test
	void rejectsUnauthenticatedRequest() throws Exception {
		mockMvc.perform(get(ADMIN_ENDPOINT))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
			.andExpect(jsonPath("$.traceId").isNotEmpty())
			.andExpect(jsonPath("$.timestamp").isString());
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void rejectsAdminAuthorityWithoutAuthenticatedUserPrincipal() throws Exception {
		mockMvc.perform(get(ADMIN_ENDPOINT))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
	}

	@Test
	void rejectsLearnerToken() throws Exception {
		User learner = saveUser(UserRole.LEARNER);

		mockMvc.perform(get(ADMIN_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, bearer(learner)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
	}

	@Test
	void rejectsInstructorToken() throws Exception {
		User instructor = saveUser(UserRole.INSTRUCTOR);

		mockMvc.perform(get(ADMIN_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, bearer(instructor)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
	}

	@Test
	void allowsAdminTokenWhenDatabaseUserIsActiveAdmin() throws Exception {
		User admin = saveUser(UserRole.ADMIN);

		mockMvc.perform(get(ADMIN_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").value("pong"));
	}

	@Test
	void rejectsStaleAdminTokenAfterDatabaseRoleDowngrade() throws Exception {
		User admin = saveUser(UserRole.ADMIN);
		String staleAdminToken = bearer(admin);
		ReflectionTestUtils.setField(admin, "role", UserRole.LEARNER);
		userRepository.saveAndFlush(admin);

		mockMvc.perform(get(ADMIN_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, staleAdminToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
			.andExpect(jsonPath("$.traceId").isNotEmpty())
			.andExpect(jsonPath("$.timestamp").isString());
	}

	@Test
	void rejectsAdminTokenWhenDatabaseUserNoLongerExists() throws Exception {
		User admin = saveUser(UserRole.ADMIN);
		String staleAdminToken = bearer(admin);
		userRepository.delete(admin);
		userRepository.flush();

		mockMvc.perform(get(ADMIN_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, staleAdminToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
	}

	@Test
	void rejectsAdminTokenWhenDatabaseUserIsDeleted() throws Exception {
		User admin = saveUser(UserRole.ADMIN);
		String staleAdminToken = bearer(admin);
		admin.withdraw();
		userRepository.saveAndFlush(admin);

		mockMvc.perform(get(ADMIN_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, staleAdminToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
	}

	@Test
	void doesNotApplyAdminInterceptorToRegularApi() throws Exception {
		User learner = saveUser(UserRole.LEARNER);

		mockMvc.perform(get("/api/users/me")
				.header(HttpHeaders.AUTHORIZATION, bearer(learner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("LEARNER"));
	}

	private User saveUser(UserRole role) {
		return userRepository.saveAndFlush(User.create(
			role.name().toLowerCase() + "@example.com",
			"password-hash",
			role.name(),
			role
		));
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class AdminTestConfiguration {

		@Bean
		AdminTestController adminTestController() {
			return new AdminTestController();
		}
	}

	@RestController
	static class AdminTestController {

		@GetMapping(ADMIN_ENDPOINT)
		ApiResponse<String> ping() {
			return ApiResponse.success("pong");
		}
	}
}
