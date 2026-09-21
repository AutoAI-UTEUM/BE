package io.edupilot.admin.xai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.admin.xai.XaiManagementClient.InvoicePreview;
import io.edupilot.admin.xai.XaiManagementClient.InvoiceSummary;
import io.edupilot.admin.xai.XaiManagementClient.PrepaidBalance;
import io.edupilot.admin.xai.XaiManagementClient.SpendingLimits;
import io.edupilot.admin.xai.dto.XaiUsageGranularity;
import io.edupilot.admin.xai.dto.XaiUsageGroupBy;
import io.edupilot.admin.xai.dto.XaiUsageMetric;
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
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private AdminXaiUsageService adminXaiUsageService;
	@MockitoBean private ReadinessService readinessService;
	@MockitoBean private XaiManagementClient xaiManagementClient;

	private MockMvc mockMvc;
	private User admin;
	private User learner;
	private User instructor;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("""
			create alias if not exists convert_tz
			for 'io.edupilot.admin.H2TimeZoneFunctions.convertTz'
			""");
		jdbcTemplate.update("delete from xai_alert_config");
		jdbcTemplate.update("delete from ai_usage_log");
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
				new BigDecimal("25.00"),
				YearMonth.now()
			)
		);
		when(xaiManagementClient.fetchInvoices(YearMonth.of(2026, 8)))
			.thenReturn(List.of(new InvoiceSummary(
				YearMonth.of(2026, 8),
				new BigDecimal("42.50"),
				new BigDecimal("50.00"),
				"PAID"
			)));
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
			post("/api/admin/xai/sync"),
			get("/api/admin/xai/usage")
				.param("from", "2026-09-01")
				.param("to", "2026-09-01"),
			get("/api/admin/xai/reconciliation")
				.param("from", "2026-08-01")
				.param("to", "2026-08-31"),
			get("/api/admin/xai/invoices")
				.param("year", "2026")
				.param("month", "8"),
			get("/api/admin/xai/alerts"),
			put("/api/admin/xai/alerts")
				.contentType(MediaType.APPLICATION_JSON)
				.content(alertBody())
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
	void exposesPhaseTwoEndpointsWithoutBillingSecrets() throws Exception {
		mockMvc.perform(get("/api/admin/xai/usage")
				.param("from", "2026-09-01")
				.param("to", "2026-09-01")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.granularity").value("DAY"))
			.andExpect(jsonPath("$.data.metric").value("COST"))
			.andExpect(jsonPath("$.data.groupBy").value("FEATURE"))
			.andExpect(jsonPath("$.data.unknownCostCalls").value(0));

		String invoices = mockMvc.perform(get("/api/admin/xai/invoices")
				.param("year", "2026")
				.param("month", "8")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].amountUsd").value("42.50"))
			.andExpect(jsonPath("$.data.items[0].status").value("PAID"))
			.andReturn().getResponse().getContentAsString();
		assertThat(invoices).doesNotContain(
			"management-api-key",
			"team-id",
			"paymentMethod",
			"billingAddress",
			"integration-secret-key",
			"integration-private-team"
		);

		mockMvc.perform(put("/api/admin/xai/alerts")
				.contentType(MediaType.APPLICATION_JSON)
				.content(alertBody())
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.balanceCriticalUsd").value("20"))
			.andExpect(jsonPath("$.data.balanceWarningUsd").value("80"));
	}

	@Test
	void rejectsUnsupportedApiKeyGroupingWithDedicatedCode() throws Exception {
		mockMvc.perform(get("/api/admin/xai/usage")
				.param("from", "2026-09-01")
				.param("to", "2026-09-01")
				.param("groupBy", "API_KEY")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_GROUP_BY"));
	}

	@Test
	void aggregatesMetricsFromInternalLogAtKstDayBoundary() {
		insertUsage(
			"2026-08-31T14:59:59Z",
			"TURN",
			"grok-before",
			1,
			1,
			1,
			10_000_000_000L,
			"before"
		);
		insertUsage(
			"2026-08-31T15:00:00Z",
			"TURN",
			"grok-a",
			1,
			2,
			3,
			10_000_000_000L,
			"inside-1"
		);
		insertUsage(
			"2026-09-01T14:59:59Z",
			"GRADE",
			"grok-b",
			4,
			5,
			6,
			null,
			"inside-2"
		);
		insertUsage(
			"2026-09-01T15:00:00Z",
			"TURN",
			"grok-after",
			1,
			1,
			1,
			10_000_000_000L,
			"after"
		);
		LocalDate day = LocalDate.of(2026, 9, 1);

		var cost = adminXaiUsageService.usage(
			day,
			day,
			XaiUsageGranularity.DAY,
			XaiUsageMetric.COST,
			XaiUsageGroupBy.FEATURE
		);
		var tokens = adminXaiUsageService.usage(
			day,
			day,
			XaiUsageGranularity.DAY,
			XaiUsageMetric.TOKENS,
			XaiUsageGroupBy.MODEL
		);

		assertThat(cost.unknownCostCalls()).isEqualTo(1);
		assertThat(cost.items()).hasSize(2);
		assertThat(cost.items().stream()
			.filter(item -> item.group().equals("TURN"))
			.findFirst().orElseThrow().costUsd())
			.isEqualByComparingTo("1");
		assertThat(cost.items().stream()
			.filter(item -> item.group().equals("GRADE"))
			.findFirst().orElseThrow().costUsd()).isNull();
		assertThat(tokens.items()).extracting(
			item -> item.group() + ":" + item.tokenCount()
		).containsExactly("grok-a:6", "grok-b:15");
	}

	@Test
	void returnsStringMoneyWithoutManagementKeyOrTeamId() throws Exception {
		String body = mockMvc.perform(get("/api/admin/xai/credits")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true))
			.andExpect(jsonPath("$.data.stale").value(false))
			.andExpect(jsonPath("$.data.prepaidBalanceUsd").value("125.00"))
			.andExpect(jsonPath("$.data.prepaidUsedThisPeriodUsd").value("25.00"))
			.andExpect(jsonPath("$.data.prepaidAvailableUsd").value("100.00"))
			.andExpect(jsonPath("$.data.currentMonthCostUsd").value("75.00"))
			.andExpect(jsonPath("$.data.postpaidLimitUsd").value("300.00"))
			.andExpect(jsonPath("$.data.postpaidUsedUsd").value("50.00"))
			.andExpect(jsonPath("$.data.postpaidRemainingUsd").value("250.00"))
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

	private String alertBody() {
		return """
			{
			  "balanceCriticalUsd": 20,
			  "balanceWarningUsd": 80,
			  "depletionCriticalDays": 5,
			  "depletionWarningDays": 20
			}
			""";
	}

	private void insertUsage(
		String createdAt,
		String feature,
		String model,
		long inputTokens,
		long outputTokens,
		long reasoningTokens,
		Long costUsdTicks,
		String requestId
	) {
		jdbcTemplate.update("""
			insert into ai_usage_log(
			  user_id, feature, model, input_tokens, output_tokens,
			  reasoning_tokens, cost_usd_ticks, request_id, success, created_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, true, ?)
			""",
			1L,
			feature,
			model,
			inputTokens,
			outputTokens,
			reasoningTokens,
			costUsdTicks,
			requestId,
			Timestamp.valueOf(LocalDateTime.ofInstant(
				Instant.parse(createdAt),
				ZoneOffset.UTC
			))
		);
	}
}
