package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.mail.EmailDelivery;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryStatus;
import io.edupilot.mail.EmailOutbox;
import io.edupilot.mail.EmailOutboxRepository;
import io.edupilot.mail.EmailOutboxStatus;
import io.edupilot.mail.EmailOutboxStore;
import io.edupilot.mail.EmailSendReservationRepository;
import io.edupilot.mail.EmailSender;
import io.edupilot.mail.EmailService;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import tools.jackson.databind.ObjectMapper;

/** Real caller/outbox transactions; the temporary H2 constraint rejects one payload INSERT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
	"spring.datasource.url=jdbc:h2:mem:password-reset-outbox-failure;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=synthetic-reset-outbox",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/password-reset-outbox-failure",
	"edupilot.admin.infra.enabled=false", "edupilot.mail.enabled=true", "edupilot.mail.provider=logging",
	"edupilot.mail.base-url=https://reset-outbox.example.test", "edupilot.mail.outbox.dispatch.mode=PAUSED",
	"edupilot.mail.outbox.recovery-delay-ms=3600000"
})
@ActiveProfiles("jpa-context")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetOutboxFailureJpaTest {
	private static final String PASSWORD = "syntheticPassword123";
	private static final String FAILURE_CONSTRAINT = "synthetic_reset_outbox_insert_failure";
	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private PasswordResetService resets;
	@Autowired private EmailService mail;
	@Autowired private EmailOutboxStore outbox;
	@Autowired private UserRepository users;
	@Autowired private PasswordResetTokenRepository tokens;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private EmailOutboxRepository jobs;
	@Autowired private EmailSendReservationRepository reservations;
	@Autowired private PasswordEncoder passwords;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private EmailSender sender;
	@MockitoBean private AiClient ai;
	private final ObjectMapper json = new ObjectMapper();

	@Test
	void payloadInsertFailureKeepsTheSame202AndRollsBackOnlyTheFailedResetRequest() throws Exception {
		// No test transaction can hide the real service proxy's commit/rollback outcome.
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertThat(AopUtils.isAopProxy(resets)).isTrue();
		assertThat(AopUtils.isAopProxy(outbox)).isTrue();
		assertThat(mockingDetails(mail).isMock()).isFalse();
		assertThat(mockingDetails(outbox).isMock()).isFalse();
		assertThat(context.getBeansOfType(SesV2Client.class)).isEmpty();
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity()).addFilters(traceIdFilter).build();
		User affected = saveUser("failed-reset@example.test");
		User unrelated = saveUser("unrelated-reset@example.test");
		User next = saveUser("next-reset@example.test");
		String originalPasswordHash = affected.getPasswordHash();
		MvcResult missing = request(mvc, "missing-reset@example.test", "192.0.2.10");
		MvcResult previous = request(mvc, unrelated.getEmail(), "192.0.2.11");
		assertThat(previous.getResponse().getStatus()).isEqualTo(202);
		PasswordResetToken previousToken = tokens.findAll().getFirst();
		Long previousJobId = jobs.findAll().getFirst().getId();
		byte[] previousPayload = payload(previousJobId);
		Instant now = Instant.now();
		PasswordResetToken olderAffectedToken = tokens.saveAndFlush(PasswordResetToken.create(
			affected, PasswordResetService.hash("A".repeat(43)), now.plusSeconds(1800), "192.0.2.12", now
		));

		// Existing jobs remain valid. The only new request inside this window reaches the
		// real EmailOutboxStore.enqueue/persist/flush and violates an actual DB constraint.
		long lastCommittedId = deliveries.findAll().stream().mapToLong(EmailDelivery::getId).max().orElseThrow();
		jdbc.execute("alter table email_outbox add constraint " + FAILURE_CONSTRAINT
			+ " check (delivery_id <= " + lastCommittedId + ")");
		MvcResult failed;
		try {
			failed = request(mvc, affected.getEmail(), "192.0.2.12");
		} finally {
			jdbc.execute("alter table email_outbox drop constraint " + FAILURE_CONSTRAINT);
		}
		// A later request must still commit both its token and its encrypted payload.
		MvcResult following = request(mvc, next.getEmail(), "192.0.2.13");
		List<PasswordResetToken> targetTokens = tokens.findAll().stream()
			.filter(token -> token.getUser().getId().equals(affected.getId())).toList();
		List<EmailDelivery> failedAudits = deliveries.findAll().stream()
			.filter(delivery -> delivery.getRecipient().equals(affected.getEmail())).toList();
		List<EmailDelivery> followingAudits = deliveries.findAll().stream()
			.filter(delivery -> delivery.getRecipient().equals(next.getEmail())).toList();
		var missingData = json.readTree(missing.getResponse().getContentAsString()).path("data");
		var failedData = json.readTree(failed.getResponse().getContentAsString()).path("data");
		var followingData = json.readTree(following.getResponse().getContentAsString()).path("data");
		List<Long> persistedJobIds = jobs.findAll().stream().map(EmailOutbox::getId).toList();
		verifyNoInteractions(sender, ai);

		// Collect response and persistence failures together so the red reproduction also
		// reports whether token invalidation, existing work, or subsequent requests leaked.
		SoftAssertions.assertSoftly(softly -> {
			softly.assertThat(missing.getResponse().getStatus()).isEqualTo(202);
			softly.assertThat(failed.getResponse().getStatus())
				.as("outbox failure must not reveal account existence through a different HTTP status")
				.isEqualTo(202);
			softly.assertThat(failedData).isEqualTo(missingData);
			softly.assertThat(following.getResponse().getStatus()).isEqualTo(202);
			softly.assertThat(followingData).isEqualTo(missingData);
			softly.assertThat(targetTokens).extracting(PasswordResetToken::getId)
				.containsExactly(olderAffectedToken.getId());
			softly.assertThat(targetTokens).extracting(PasswordResetToken::getUsedAt).containsOnlyNulls();
			softly.assertThat(users.findById(affected.getId()).orElseThrow().getPasswordHash())
				.isEqualTo(originalPasswordHash);
			softly.assertThat(tokens.findById(previousToken.getId()).orElseThrow().getUsedAt()).isNull();
			softly.assertThat(tokens.findById(previousToken.getId()).orElseThrow().getTokenHash())
				.isEqualTo(previousToken.getTokenHash());
			softly.assertThat(payload(previousJobId)).isEqualTo(previousPayload);
			softly.assertThat(jobs.findById(previousJobId).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.READY);
			softly.assertThat(failedAudits).extracting(EmailDelivery::getStatus).containsExactly(EmailDeliveryStatus.FAILED);
			softly.assertThat(failedAudits).extracting(EmailDelivery::getErrorSummary).containsExactly("OUTBOX_QUEUE_FAILED");
			softly.assertThat(failedAudits).extracting(EmailDelivery::getAttemptCount).containsExactly(0);
			softly.assertThat(failedAudits).allMatch(delivery -> !persistedJobIds.contains(delivery.getId()));
			softly.assertThat(followingAudits).extracting(EmailDelivery::getStatus).containsExactly(EmailDeliveryStatus.QUEUED);
			softly.assertThat(followingAudits).allMatch(delivery -> persistedJobIds.contains(delivery.getId()));
			softly.assertThat(tokens.findAll().stream()
				.filter(token -> token.getUser().getId().equals(next.getId())).toList()).hasSize(1);
			softly.assertThat(tokens.count()).isEqualTo(3);
			softly.assertThat(jobs.count()).isEqualTo(2);
			softly.assertThat(jobs.findAll()).extracting(EmailOutbox::getStatus).containsOnly(EmailOutboxStatus.READY);
			softly.assertThat(reservations.count()).isZero();
		});
	}

	private User saveUser(String email) {
		return users.saveAndFlush(User.create(email, passwords.encode(PASSWORD), "Synthetic learner"));
	}

	private MvcResult request(MockMvc mvc, String email, String ip) throws Exception {
		return mvc.perform(post("/api/auth/password-reset/request")
				.with(request -> { request.setRemoteAddr(ip); return request; })
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("email", email))))
			.andReturn();
	}

	private byte[] payload(Long id) {
		return jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id = ?", byte[].class, id);
	}
}
