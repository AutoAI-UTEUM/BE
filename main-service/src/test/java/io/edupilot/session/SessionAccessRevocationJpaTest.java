package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.VerifiedTestUsers;
import io.edupilot.admin.AdminUserService;
import io.edupilot.ai.AiClient;
import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.UserAccessGuard;
import io.edupilot.classroom.*;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.*;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.report.*;
import io.edupilot.user.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
	"spring.datasource.url=jdbc:h2:mem:session-access-revocation;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=synthetic-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/session-access-revocation"
})
@ActiveProfiles("jpa-context")
class SessionAccessRevocationJpaTest {
	@DynamicPropertySource
	static void isolatedMysql(DynamicPropertyRegistry registry) {
		if (!"true".equals(System.getenv("RUNTIME_REGRESSIONS_MYSQL"))) { return; }
		registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:33316/runtime_access_synthetic");
		registry.add("spring.datasource.username", () -> "root");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}
	@Autowired private WebApplicationContext context;
	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ClassroomWeekRepository weeks;
	@Autowired private ClassroomWeekMaterialRepository links;
	@Autowired private ClassroomStudentService students;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private MaterialWithdrawalHook materialWithdrawal;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private ReportGenerationRepository reports;
	@Autowired private AdminUserService adminService;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private AiClient ai;
	@MockitoBean private FileStorage files;
	@MockitoSpyBean private UserAccessGuard access;
	private final Map<UserRole, User> outsiders = new EnumMap<>(UserRole.class);
	private MockMvc mvc;
	private User instructor;
	private User learner;
	private Classroom classroom;
	private ClassroomWeekMaterial link;
	private LearningMaterial material;
	private LearningSession session;
	private ReportGeneration report;

	@BeforeEach
	void setUp() {
		if ("true".equals(System.getenv("RUNTIME_REGRESSIONS_MYSQL"))) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("runtime_access_synthetic");
		}
		if (jdbc.queryForObject("select count(*) from deletion_journal_lock where id=1", Integer.class) == 0) {
			jdbc.update("insert into deletion_journal_lock(id) values(1)");
		}
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		instructor = user(UserRole.INSTRUCTOR);
		learner = user(UserRole.LEARNER);
		for (UserRole role : UserRole.values()) { outsiders.put(role, user(role)); }
		classroom = classrooms.saveAndFlush(Classroom.create(instructor, "Synthetic classroom",
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15), ClassroomColor.BLUE, null,
			UUID.randomUUID().toString().substring(0, 10)));
		members.saveAndFlush(ClassroomMember.create(classroom, learner, Instant.now()));
		var week = weeks.saveAndFlush(ClassroomWeek.create(classroom, 1, "Synthetic week", null,
			ClassroomWeekStatus.PUBLISHED, 1));
		material = LearningMaterial.create(instructor, "Synthetic PDF", "materials/" + UUID.randomUUID() + ".pdf");
		material.markReady(1);
		material = materials.saveAndFlush(material);
		link = links.saveAndFlush(ClassroomWeekMaterial.create(week, material, Instant.now()));
		session = sessions.saveAndFlush(LearningSession.create(learner, material));
		report = reports.saveAndFlush(ReportGeneration.create(classroom, learner, instructor,
			UUID.randomUUID().toString(), ReportScopeType.FULL, null, "a".repeat(64), "1.0"));
		when(files.load(material.getStorageKey())).thenReturn(new ByteArrayResource("%PDF-synthetic".getBytes()));
	}

	@ParameterizedTest
	@EnumSource(UserRole.class)
	void otherLearnerInstructorAndAdminCannotReadForeignSessionFileOrSse(UserRole role) throws Exception {
		User stranger = outsiders.get(role);
		assertStatus(stranger, "/api/sessions/" + session.getId(), 404);
		assertStatus(stranger, "/api/sessions/" + session.getId() + "/messages", 404);
		assertStatus(stranger, "/api/sessions/" + session.getId() + "/stream", 404);
		assertStatus(stranger, "/api/materials/" + material.getId() + "/file", 404);
		assertStatus(stranger, "/api/reports/" + report.getId(), role == UserRole.INSTRUCTOR ? 404 : 403);
		verifyNoInteractions(ai, files);
	}

	@Test
	void currentMemberCanReadFileAndOwnSseWithoutAiCall() throws Exception {
		assertStatus(learner, "/api/materials/" + material.getId() + "/file", 200);
		assertStatus(learner, "/api/sessions/" + session.getId() + "/stream", 200);
		verify(files).load(material.getStorageKey());
		verifyNoInteractions(ai);
	}

	@Test
	void membershipRemovalRevokesFileSseTurnAndInstructorReport() throws Exception {
		assertStatus(learner, "/api/materials/" + material.getId() + "/file", 200);
		assertStatus(instructor, "/api/reports/" + report.getId(), 200);
		students.remove(instructor.getId(), UserRole.INSTRUCTOR, classroom.getId(), learner.getId());
		clearInvocations(files);
		assertStatus(learner, "/api/materials/" + material.getId() + "/file", 404);
		assertStatus(instructor, "/api/reports/" + report.getId(), 404);
		var turn = mvc.perform(post("/api/sessions/" + session.getId() + "/turns")
			.header(HttpHeaders.AUTHORIZATION, bearer(learner)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"requestId\":\"synthetic-revoked\",\"eventType\":\"USER_QUESTION\",\"payload\":{\"message\":\"Synthetic question\"}}"))
			.andReturn();
		assertThat(turn.getResponse().getStatus()).isEqualTo(404);
		assertStatus(learner, "/api/sessions/" + session.getId() + "/stream", 404);
		verifyNoInteractions(ai, files);
	}

	@Test
	void unlinkRevokesSseWhileTheOwnedSessionStillExistsAndIsActive() throws Exception {
		links.delete(link);
		assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.ACTIVE);
		assertStatus(learner, "/api/materials/" + material.getId() + "/file", 404);
		assertStatus(learner, "/api/sessions/" + session.getId() + "/stream", 404);
		verifyNoInteractions(ai, files);
	}

	@Test
	void ownerWithdrawalMaterialCleanupRevokesNewSse() throws Exception {
		new TransactionTemplate(transactions).executeWithoutResult(
			status -> materialWithdrawal.onWithdraw(instructor.getId()));
		assertStatus(learner, "/api/materials/" + material.getId() + "/file", 404);
		assertStatus(learner, "/api/sessions/" + session.getId() + "/stream", 404);
		verifyNoInteractions(ai, files);
	}

	@Test
	void anotherInstanceMustRejectOldInstructorTokenAfterRoleChange() throws Exception {
		UserAccessGuard otherInstance = warmedOtherInstance(instructor);
		adminService.changeRole(outsiders.get(UserRole.ADMIN).getId(), instructor.getId(), UserRole.LEARNER);
		doAnswer(call -> otherInstance.check(call.getArgument(0))).when(access).check(any());
		assertStatus(instructor, "/api/reports/" + report.getId(), 401);
		verifyNoInteractions(ai, files);
	}

	@Test
	void anotherInstanceMustRejectSuspendedAccountBeforeReadingPdfOrOpeningSse() throws Exception {
		UserAccessGuard otherInstance = warmedOtherInstance(learner);
		adminService.suspend(outsiders.get(UserRole.ADMIN).getId(), learner.getId(), "Synthetic suspension");
		assertThat(otherInstance.check(new AuthenticatedUser(learner.getId(), UserRole.LEARNER)))
			.isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
		doAnswer(call -> otherInstance.check(call.getArgument(0))).when(access).check(any());
		assertStatus(learner, "/api/materials/" + material.getId() + "/file", 401);
		assertStatus(learner, "/api/sessions/" + session.getId() + "/stream", 401);
		verifyNoInteractions(ai, files);
	}

	private UserAccessGuard warmedOtherInstance(User user) {
		UserAccessGuard guard = new UserAccessGuard(users);
		assertThat(guard.check(new AuthenticatedUser(user.getId(), user.getRole()))).isNull();
		return guard;
	}
	private User user(UserRole role) {
		return users.saveAndFlush(VerifiedTestUsers.legacyVerified(User.create(
			"synthetic-" + UUID.randomUUID() + "@example.com", "hash", "Synthetic " + role, role)));
	}
	private String bearer(User user) { return "Bearer " + tokens.createAccessToken(user); }
	private void assertStatus(User user, String path, int expected) throws Exception {
		MvcResult result = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(user))).andReturn();
		if (result.getRequest().isAsyncStarted()) { result.getRequest().getAsyncContext().complete(); }
		assertThat(result.getResponse().getStatus()).as(path).isEqualTo(expected);
	}
}
