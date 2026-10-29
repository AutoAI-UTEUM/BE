package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.deletion.DeletionJournalLock;
import io.edupilot.deletion.DeletionJournalLockRepository;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.policy.PolicyConsentRepository;
import io.edupilot.policy.PolicyDocument;
import io.edupilot.policy.PolicyDocumentRepository;
import io.edupilot.policy.PolicyType;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserStatus;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:mail-dispatch-lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/mail-dispatch-lifecycle",
	"edupilot.mail.enabled=true", "edupilot.mail.provider=logging",
	"edupilot.mail.base-url=https://dev.uteum.com", "edupilot.policy.signup-consent-required=true",
	"edupilot.mail.outbox.recovery-delay-ms=3600000",
	"edupilot.mail.outbox.dispatch.mode=ISOLATED_TRIAL",
	"edupilot.mail.outbox.dispatch.delivery-ids=1,2,3",
	"edupilot.mail.outbox.dispatch.recipient=trial@example.test"
})
@ActiveProfiles("jpa-context")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceMailDispatchLifecycleJpaTest {
	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traces;
	@Autowired private PolicyDocumentRepository policies;
	@Autowired private PolicyConsentRepository consents;
	@Autowired private UserRepository users;
	@Autowired private EmailOutboxStore outbox;
	@Autowired private EmailOutboxRepository jobs;
	@Autowired private EmailDeliveryStore history;
	@Autowired private EmailQuotaLockRepository quotaLocks;
	@Autowired private DeletionJournalLockRepository deletionLocks;
	@Autowired private MailProperties properties;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private Clock clock;
	@MockitoBean private EmailSender sender;
	@MockitoBean private AiClient ai;
	@MockitoBean(name = "mailExecutor") private Executor lostExecutor;
	private final List<EmailMessage> messages = new CopyOnWriteArrayList<>();

	@Test void realSignupVerificationResetAndWithdrawalFlowsRecoverOnlyTheirApprovedJobs() throws Exception {
		quotaLocks.saveAndFlush(EmailQuotaLock.initial());
		deletionLocks.saveAndFlush(DeletionJournalLock.initial());
		for (PolicyType type : PolicyType.values()) {
			policies.saveAndFlush(PolicyDocument.create(type, "trial-v1", "Synthetic policy", "Synthetic terms",
				null, true, clock.instant().minusSeconds(1), 999L, clock.instant()));
		}
		when(sender.send(any())).thenAnswer(invocation -> {
			messages.add(invocation.getArgument(0));
			return new EmailDeliveryResult("synthetic-lifecycle-receipt");
		});
		// A prior unrelated job is present throughout the lifecycle. These are fixture-only H2 IDs.
		jdbc.update("insert into email_deliveries(id,recipient,type,status,subject,created_at,attempt_count) "
			+ "values(100,'other@example.test','NOTIFICATION','QUEUED','Synthetic backlog',?,0)",
			Timestamp.from(clock.instant()));
		outbox.enqueue(100L, new EmailMessage("other@example.test", "Synthetic backlog", "Synthetic body", null,
			EmailDeliveryType.NOTIFICATION), clock.instant().plusSeconds(86400));
		jdbc.execute("alter table email_deliveries alter column id restart with 1");
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(traces).apply(springSecurity()).build();

		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
			{"email":"trial@example.test","password":"password123","name":"Synthetic learner","role":"LEARNER",
			 "dateOfBirth":"2000-01-01","consents":[{"type":"TERMS","version":"trial-v1"},
			 {"type":"PRIVACY","version":"trial-v1"}]}
			""")).andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerificationRequired").value(true));
		Long userId = users.findByEmail("trial@example.test").orElseThrow().getId();
		assertThat(consents.findByUser_IdOrderByAgreedAtDescIdDesc(userId)).hasSize(2);
		assertThat(messages).isEmpty();
		String bearer = login(mvc, "password123");
		mvc.perform(get("/api/materials").header("Authorization", bearer)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
		worker().recoverPending();
		assertThat(messages).hasSize(1);
		String verificationToken = token(messages.getFirst(), "/verify-email#token=([A-Za-z0-9_-]{43})");
		mvc.perform(post("/api/auth/email-verification/confirm").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + verificationToken + "\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerificationRequired").value(false));
		mvc.perform(get("/api/materials").header("Authorization", bearer)).andExpect(status().isOk());

		mvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"trial@example.test\"}")).andExpect(status().isAccepted());
		worker().recoverPending();
		assertThat(messages).hasSize(2);
		String resetToken = token(messages.get(1), "/reset-password\\?token=([A-Za-z0-9_-]{43})");
		mvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + resetToken + "\",\"newPassword\":\"newpassword456\"}"))
			.andExpect(status().isOk());
		bearer = login(mvc, "newpassword456");
		mvc.perform(delete("/api/users/me").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"newpassword456\"}")).andExpect(status().isOk());
		worker().recoverPending();
		worker().recoverPending();
		worker().kick(100L);

		assertThat(messages).extracting(EmailMessage::type).containsExactly(
			EmailDeliveryType.EMAIL_VERIFY, EmailDeliveryType.PASSWORD_RESET, EmailDeliveryType.NOTIFICATION);
		assertThat(messages).allMatch(message -> message.to().equals("trial@example.test"));
		assertThat(messages.get(2).subject()).isEqualTo("[UTEUM] 회원 탈퇴 완료");
		assertThat(users.findById(userId).orElseThrow().getStatus()).isEqualTo(UserStatus.DELETED);
		for (long id = 1; id <= 3; id++) {
			assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.SENT);
			assertThat(jobs.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
			assertThat(jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id=?",
				byte[].class, id)).isNull();
		}
		assertThat(jobs.findById(100L).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.READY);
		mvc.perform(get("/api/users/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
		verify(sender, times(3)).send(any());
	}

	private String login(MockMvc mvc, String password) throws Exception {
		String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"trial@example.test\",\"password\":\"" + password + "\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonMapper.builder().build().readTree(response).path("data").path("accessToken").asText();
	}

	private String token(EmailMessage message, String expression) {
		var token = Pattern.compile(expression).matcher(message.textBody());
		assertThat(token.find()).isTrue();
		return token.group(1);
	}

	private EmailOutboxWorker worker() {
		return new EmailOutboxWorker(outbox, history, sender, properties, Runnable::run);
	}
}
