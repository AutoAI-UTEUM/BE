package io.edupilot.admin.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryStore;
import io.edupilot.mail.EmailDeliveryStatus;
import io.edupilot.mail.EmailDeliveryType;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailSender;
import io.edupilot.mail.EmailService;
import io.edupilot.mail.MailProperties;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:admin-mail;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/admin-mail",
		"edupilot.admin.infra.enabled=false",
		"edupilot.mail.enabled=false"
	}
)
@ActiveProfiles("jpa-context")
@ExtendWith(OutputCaptureExtension.class)
class AdminMailIntegrationTest {

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private UserRepository userRepository;
	@Autowired private EmailDeliveryRepository deliveryRepository;
	@Autowired private EmailDeliveryStore deliveryStore;
	@Autowired private EmailService emailService;
	@Autowired private PlatformTransactionManager transactionManager;

	private MockMvc mockMvc;
	private User admin;
	private User learner;
	private User instructor;

	@BeforeEach
	void setUp() {
		deliveryRepository.deleteAll();
		userRepository.deleteAll();
		admin = saveUser(UserRole.ADMIN);
		learner = saveUser(UserRole.LEARNER);
		instructor = saveUser(UserRole.INSTRUCTOR);
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
	}

	@Test
	void adminCanTestAndListHistoryWithoutMessageBody(CapturedOutput output) throws Exception {
		mockMvc.perform(post("/api/admin/mail/test")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"to\":\"person@example.com\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.deliveryId").isNumber());

		String response = mockMvc.perform(get("/api/admin/mail/deliveries")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].status").value("FAILED"))
			.andExpect(jsonPath("$.data.items[0].errorSummary").value("DISABLED"))
			.andExpect(jsonPath("$.data.items[0].type").value("TEST"))
			.andReturn().getResponse().getContentAsString();
		assertThat(response).doesNotContain("UTEUM 시스템 메일 발송 테스트입니다", "textBody", "htmlBody");
		assertThat(output).contains("ADMIN_MAIL_TEST")
			.doesNotContain("UTEUM 시스템 메일 발송 테스트입니다");
	}

	@Test
	void learnerAndInstructorCannotUseEitherEndpoint() throws Exception {
		for (User user : new User[] {learner, instructor}) {
			mockMvc.perform(post("/api/admin/mail/test")
					.header(HttpHeaders.AUTHORIZATION, bearer(user))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"to\":\"person@example.com\"}"))
				.andExpect(status().isForbidden());
			mockMvc.perform(get("/api/admin/mail/deliveries")
					.header(HttpHeaders.AUTHORIZATION, bearer(user)))
				.andExpect(status().isForbidden());
		}
	}

	@Test
	void adminTestIsLimitedToOncePerMinute() throws Exception {
		for (int expectedStatus : new int[] {200, 429}) {
			mockMvc.perform(post("/api/admin/mail/test")
					.header(HttpHeaders.AUTHORIZATION, bearer(admin))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"to\":\"person@example.com\"}"))
				.andExpect(status().is(expectedStatus));
		}
	}

	@Test
	void historySurvivesCallerTransactionRollback() {
		AtomicReference<Long> id = new AtomicReference<>();
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			id.set(emailService.sendAsync(new EmailMessage(
				"person@example.com", "test", "secret", null, EmailDeliveryType.TEST
			)));
			transaction.setRollbackOnly();
		});
		assertThat(deliveryRepository.findById(id.get()).orElseThrow().getStatus())
			.isEqualTo(EmailDeliveryStatus.FAILED);
	}

	@Test
	void databaseQuotaCountsQueuedRowsAndRejectsSixthRecipientDelivery() {
		Long lastId = null;
		for (int index = 0; index < 6; index++) {
			lastId = deliveryStore.queue(new EmailMessage(
				"limit@example.com", "test", "body", null, EmailDeliveryType.TEST
			));
		}
		assertThat(deliveryStore.reserve(lastId)).isFalse();
		assertThat(deliveryRepository.findById(lastId).orElseThrow().getStatus())
			.isEqualTo(EmailDeliveryStatus.RATE_LIMITED);
	}

	@Test
	void rollbackKeepsHistoryButNeverDispatchesMail() {
		EmailSender sender = mock(EmailSender.class);
		EmailService enabledService = new EmailService(
			deliveryStore, sender, Runnable::run,
			new MailProperties(true, "ses", "no-reply@uteum.com", "",
				"https://www.uteum.com", "ap-northeast-2")
		);
		AtomicReference<Long> id = new AtomicReference<>();
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			id.set(enabledService.sendAsync(new EmailMessage(
				"rollback@example.com", "test", "secret", null, EmailDeliveryType.TEST
			)));
			transaction.setRollbackOnly();
		});
		assertThat(deliveryRepository.findById(id.get()).orElseThrow().getErrorSummary())
			.isEqualTo("CALLER_TRANSACTION_ROLLED_BACK");
		verifyNoInteractions(sender);
	}

	private User saveUser(UserRole role) {
		return userRepository.saveAndFlush(User.create(
			role.name().toLowerCase() + "-mail@example.com",
			"password-hash", role.name(), role
		));
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user);
	}
}
