package io.edupilot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageLogRepository;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:admin-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.generate_statistics=true",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/admin-api"
	}
)
@ActiveProfiles("jpa-context")
@Import(AdminApiIntegrationTest.FixedAdminAiUsageServiceConfiguration.class)
class AdminApiIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private AiUsageLogRepository usageLogRepository;
	@Autowired private AdminClassroomService adminClassroomService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;

	private MockMvc mockMvc;
	private User admin;
	private User instructor;
	private User learner;
	private User deletedUser;
	private Classroom firstClassroom;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("""
			create alias if not exists convert_tz
			for 'io.edupilot.admin.H2TimeZoneFunctions.convertTz'
			""");
		usageLogRepository.deleteAll();
		memberRepository.deleteAll();
		classroomRepository.deleteAll();
		userRepository.deleteAll();

		admin = saveUser("admin@example.com", "관리자", UserRole.ADMIN);
		instructor = saveUser(
			"instructor@example.com",
			"강사",
			UserRole.INSTRUCTOR
		);
		learner = saveUser("learner@example.com", "학습자", UserRole.LEARNER);
		deletedUser = saveUser(
			"withdrawn@example.com",
			"탈퇴 예정",
			UserRole.LEARNER
		);
		deletedUser.withdraw();
		userRepository.saveAndFlush(deletedUser);

		firstClassroom = saveClassroom("가 강의실", "admin-room-1");
		Classroom second = saveClassroom("나 강의실", "admin-room-2");
		saveClassroom("다 강의실", "admin-room-3");
		saveClassroom("라 강의실", "admin-room-4");
		memberRepository.saveAndFlush(ClassroomMember.create(
			firstClassroom,
			learner,
			Instant.parse("2026-08-20T00:00:00Z")
		));
		memberRepository.saveAndFlush(ClassroomMember.create(
			second,
			learner,
			Instant.parse("2026-08-21T00:00:00Z")
		));
		memberRepository.saveAndFlush(ClassroomMember.create(
			second,
			deletedUser,
			Instant.parse("2026-08-22T00:00:00Z")
		));

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
	}

	@Test
	void enforcesAdminAccessForEveryReadEndpoint() throws Exception {
		List<String> endpoints = List.of(
			"/api/admin/users",
			"/api/admin/users/" + learner.getId(),
			"/api/admin/classrooms",
			"/api/admin/classrooms/" + firstClassroom.getId(),
			"/api/admin/ai-usage/summary?from=2026-08-23&to=2026-08-29",
			"/api/admin/ai-usage/users?from=2026-08-23&to=2026-08-29"
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
	void listsSearchesAndFiltersUsersIncludingDeletedAccounts() throws Exception {
		User googleUser = userRepository.saveAndFlush(User.createGoogle(
			"case.match@example.com",
			"password-hash",
			"Search Person",
			UserRole.INSTRUCTOR,
			"EduPilot",
			true,
			"terms-v1",
			"privacy-v1",
			Instant.parse("2026-08-01T00:00:00Z"),
			"private-google-sub"
		));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("page", "0")
				.param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(2))
			.andExpect(jsonPath("$.data.totalElements").value(5));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("q", "CASE.MATCH"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.items[0].id").value(googleUser.getId()));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("q", "search person"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.items[0].email")
				.value("case.match@example.com"));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("role", "INSTRUCTOR")
				.param("status", "ACTIVE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("status", "DELETED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.items[0].id").value(deletedUser.getId()))
			.andExpect(jsonPath("$.data.items[0].status").value("DELETED"));
	}

	@Test
	void userResponsesCannotSerializeCredentialFields() throws Exception {
		User googleUser = userRepository.saveAndFlush(User.createGoogle(
			"secret@example.com",
			"private-password-hash",
			"민감정보 검증",
			UserRole.LEARNER,
			"EduPilot",
			false,
			"terms-v1",
			"privacy-v1",
			Instant.parse("2026-08-01T00:00:00Z"),
			"private-google-sub"
		));

		String listBody = mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String detailBody = mockMvc.perform(get(
				"/api/admin/users/" + googleUser.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.affiliation").value("EduPilot"))
			.andExpect(jsonPath("$.data.consentedAt").isString())
			.andReturn().getResponse().getContentAsString();

		assertNoCredentialKeys(listBody);
		assertNoCredentialKeys(detailBody);
	}

	@Test
	void returnsClassroomMemberCountsAndDetailsWithoutNPlusOne() throws Exception {
		mockMvc.perform(get("/api/admin/classrooms")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("sort", "NAME"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].id")
				.value(firstClassroom.getId()))
			.andExpect(jsonPath("$.data.items[0].memberCount").value(1))
			.andExpect(jsonPath("$.data.items[1].memberCount").value(2));

		mockMvc.perform(get("/api/admin/classrooms/" + firstClassroom.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.instructor.id").value(instructor.getId()))
			.andExpect(jsonPath("$.data.memberCount").value(1))
			.andExpect(jsonPath("$.data.members[0].userId").value(learner.getId()))
			.andExpect(jsonPath("$.data.members[0].role").value("LEARNER"))
			.andExpect(jsonPath("$.data.members[0].joinedAt").isString());

		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class)
			.getStatistics();
		statistics.clear();
		adminClassroomService.list(AdminListSort.RECENT, 0, 1);
		long oneItemQueryCount = statistics.getPrepareStatementCount();

		entityManager.clear();
		statistics.clear();
		adminClassroomService.list(AdminListSort.RECENT, 0, 3);
		long threeItemQueryCount = statistics.getPrepareStatementCount();

		assertThat(oneItemQueryCount).isEqualTo(threeItemQueryCount);
		assertThat(threeItemQueryCount).isLessThanOrEqualTo(3);
	}

	@Test
	void aggregatesDailyAndFeatureUsageAcrossKstMidnight() throws Exception {
		insertUsage(
			learner.getId(),
			AiFeature.DOC_CHAT,
			true,
			10L,
			20L,
			null,
			Instant.parse("2026-08-25T14:59:59Z")
		);
		insertUsage(
			learner.getId(),
			AiFeature.TURN,
			false,
			null,
			null,
			null,
			Instant.parse("2026-08-25T15:00:00Z")
		);
		insertUsage(
			instructor.getId(),
			AiFeature.TURN,
			true,
			5L,
			null,
			2L,
			Instant.parse("2026-08-26T01:00:00Z")
		);

		mockMvc.perform(get("/api/admin/ai-usage/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("from", "2026-08-25")
				.param("to", "2026-08-26"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.daily.length()").value(2))
			.andExpect(jsonPath("$.data.daily[0].date").value("2026-08-25"))
			.andExpect(jsonPath("$.data.daily[0].callCount").value(1))
			.andExpect(jsonPath("$.data.daily[0].successCount").value(1))
			.andExpect(jsonPath("$.data.daily[0].failCount").value(0))
			.andExpect(jsonPath("$.data.daily[0].inputTokens").value(10))
			.andExpect(jsonPath("$.data.daily[0].outputTokens").value(20))
			.andExpect(jsonPath("$.data.daily[0].reasoningTokens").isEmpty())
			.andExpect(jsonPath("$.data.daily[1].date").value("2026-08-26"))
			.andExpect(jsonPath("$.data.daily[1].callCount").value(2))
			.andExpect(jsonPath("$.data.daily[1].successCount").value(1))
			.andExpect(jsonPath("$.data.daily[1].failCount").value(1))
			.andExpect(jsonPath("$.data.daily[1].inputTokens").value(5))
			.andExpect(jsonPath("$.data.daily[1].outputTokens").isEmpty())
			.andExpect(jsonPath("$.data.daily[1].reasoningTokens").value(2))
			.andExpect(jsonPath("$.data.features[0].feature").value("DOC_CHAT"))
			.andExpect(jsonPath("$.data.features[0].callCount").value(1))
			.andExpect(jsonPath("$.data.features[1].feature").value("TURN"))
			.andExpect(jsonPath("$.data.features[1].callCount").value(2));
	}

	@Test
	void returnsTopUsersIncludingDeletedAccountsAndHonorsLimit() throws Exception {
		for (int index = 0; index < 4; index++) {
			insertUsage(
				deletedUser.getId(),
				AiFeature.TURN,
				true,
				1L,
				2L,
				null,
				Instant.parse("2026-08-25T00:00:00Z").plusSeconds(index)
			);
		}
		for (int index = 0; index < 3; index++) {
			insertUsage(
				instructor.getId(),
				AiFeature.DOC_CHAT,
				true,
				3L,
				4L,
				1L,
				Instant.parse("2026-08-25T01:00:00Z").plusSeconds(index)
			);
		}
		insertUsage(
			learner.getId(),
			AiFeature.TURN,
			false,
			null,
			null,
			null,
			Instant.parse("2026-08-25T02:00:00Z")
		);

		mockMvc.perform(get("/api/admin/ai-usage/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("from", "2026-08-25")
				.param("to", "2026-08-25")
				.param("limit", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].userId")
				.value(deletedUser.getId()))
			.andExpect(jsonPath("$.data.items[0].status").value("DELETED"))
			.andExpect(jsonPath("$.data.items[0].callCount").value(4))
			.andExpect(jsonPath("$.data.items[0].inputTokens").value(4))
			.andExpect(jsonPath("$.data.items[1].userId")
				.value(instructor.getId()))
			.andExpect(jsonPath("$.data.items[1].callCount").value(3));
	}

	@Test
	void validatesDateRangeAndUsesRecentSevenKstDaysByDefault() throws Exception {
		mockMvc.perform(get("/api/admin/ai-usage/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("from", "2026-08-30")
				.param("to", "2026-08-29"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		mockMvc.perform(get("/api/admin/ai-usage/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("from", "2026-01-01")
				.param("to", "2026-04-03"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		mockMvc.perform(get("/api/admin/ai-usage/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("limit", "101"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		insertUsage(
			learner.getId(),
			AiFeature.TURN,
			true,
			1L,
			1L,
			1L,
			Instant.parse("2026-08-22T14:59:59Z")
		);
		insertUsage(
			learner.getId(),
			AiFeature.TURN,
			true,
			2L,
			2L,
			2L,
			Instant.parse("2026-08-22T15:00:00Z")
		);

		mockMvc.perform(get("/api/admin/ai-usage/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.daily.length()").value(1))
			.andExpect(jsonPath("$.data.daily[0].date").value("2026-08-23"))
			.andExpect(jsonPath("$.data.daily[0].callCount").value(1))
			.andExpect(jsonPath("$.data.daily[0].inputTokens").value(2));
	}

	private User saveUser(String email, String name, UserRole role) {
		return userRepository.saveAndFlush(User.create(
			email,
			"password-hash",
			name,
			role
		));
	}

	private Classroom saveClassroom(String name, String inviteCode) {
		return classroomRepository.saveAndFlush(Classroom.create(
			instructor,
			name,
			LocalDate.of(2026, 8, 1),
			LocalDate.of(2026, 12, 31),
			ClassroomColor.BLUE,
			"관리자 조회 테스트",
			inviteCode
		));
	}

	private void insertUsage(
		Long userId,
		AiFeature feature,
		boolean success,
		Long inputTokens,
		Long outputTokens,
		Long reasoningTokens,
		Instant createdAt
	) {
		jdbcTemplate.update("""
			insert into ai_usage_log (
				user_id,
				feature,
				model,
				input_tokens,
				output_tokens,
				reasoning_tokens,
				success,
				created_at
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			""",
			userId,
			feature.name(),
			"test-model",
			inputTokens,
			outputTokens,
			reasoningTokens,
			success,
			Timestamp.valueOf(LocalDateTime.ofInstant(
				createdAt,
				ZoneOffset.UTC
			))
		);
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user);
	}

	private void assertNoCredentialKeys(String body) {
		assertThat(body)
			.doesNotContain(
				"passwordHash",
				"password_hash",
				"googleSub",
				"google_sub",
				"refreshToken",
				"refresh_token"
			);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedAdminAiUsageServiceConfiguration {

		@Bean
		@Primary
		AdminAiUsageService fixedAdminAiUsageService(
			AiUsageLogRepository usageLogRepository
		) {
			return new AdminAiUsageService(
				usageLogRepository,
				Clock.fixed(NOW, ZoneOffset.UTC)
			);
		}
	}
}
