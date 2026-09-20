package io.edupilot.admin.xai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.admin.xai.XaiManagementClient.InvoicePreview;
import io.edupilot.admin.xai.XaiManagementClient.PrepaidBalance;
import io.edupilot.admin.xai.XaiManagementClient.SpendingLimits;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.global.config.ReadinessResponse;
import io.edupilot.global.config.ReadinessService;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:admin-xai;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/admin-xai",
		"edupilot.admin.infra.enabled=false",
		"edupilot.admin.xai.management-api-key=integration-secret-key",
		"edupilot.admin.xai.team-id=integration-private-team"
	}
)
@ActiveProfiles("jpa-context")
class AdminXaiIntegrationTest {

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private UserRepository userRepository;
	@MockitoBean private ReadinessService readinessService;
	@MockitoBean private XaiManagementClient xaiManagementClient;

	private MockMvc mockMvc;
	private User admin;
	private User learner;
	private User instructor;

	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		admin = saveUser("admin-xai@example.com", UserRole.ADMIN);
		learner = saveUser("learner-xai@example.com", UserRole.LEARNER);
		instructor = saveUser("instructor-xai@example.com", UserRole.INSTRUCTOR);
		when(readinessService.check()).thenReturn(ReadinessResponse.of(true, true));
		when(xaiManagementClient.fetchPrepaidBalance())
			.thenReturn(new PrepaidBalance(new BigDecimal("125.00")));
		when(xaiManagementClient.fetchSpendingLimits())
			.thenReturn(new SpendingLimits(new BigDecimal("300.00")));
		when(xaiManagementClient.fetchInvoicePreview()).thenReturn(
			new InvoicePreview(
				new BigDecimal("75.00"),
				YearMonth.now()
			)
		);
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
	}

	@Test
	void enforcesAdminAccessForEveryXaiEndpoint() throws Exception {
		List<MockHttpServletRequestBuilder> requests = List.of(
			get("/api/admin/xai/credits"),
			get("/api/admin/xai/status"),
			get("/api/admin/xai/overview"),
			post("/api/admin/xai/sync")
		);

		for (MockHttpServletRequestBuilder request : requests) {
			mockMvc.perform(request)
				.andExpect(status().isUnauthorized());
		}
		for (MockHttpServletRequestBuilder request : requests) {
			mockMvc.perform(request
					.header(HttpHeaders.AUTHORIZATION, bearer(learner)))
				.andExpect(status().isForbidden());
		}
		for (MockHttpServletRequestBuilder request : requests) {
			mockMvc.perform(request
					.header(HttpHeaders.AUTHORIZATION, bearer(instructor)))
				.andExpect(status().isForbidden());
		}
	}

	@Test
	void returnsStringMoneyWithoutManagementKeyOrTeamId() throws Exception {
		String body = mockMvc.perform(get("/api/admin/xai/credits")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true))
			.andExpect(jsonPath("$.data.stale").value(false))
			.andExpect(jsonPath("$.data.prepaidBalanceUsd").value("125.00"))
			.andExpect(jsonPath("$.data.postpaidLimitUsd").value("300.00"))
			.andExpect(jsonPath("$.data.postpaidUsedUsd").value("75.00"))
			.andExpect(jsonPath("$.data.postpaidRemainingUsd").value("225.00"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body)
			.doesNotContain("integration-secret-key", "integration-private-team");
	}

	@Test
	void syncRefreshesAndRateLimitsSecondRequestForSameAdmin() throws Exception {
		mockMvc.perform(post("/api/admin/xai/sync")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true));

		mockMvc.perform(post("/api/admin/xai/sync")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"));
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
