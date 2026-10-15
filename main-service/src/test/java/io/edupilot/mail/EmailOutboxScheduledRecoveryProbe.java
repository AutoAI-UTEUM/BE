package io.edupilot.mail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

import io.edupilot.auth.JwtProperties;
import io.edupilot.exam.ExamGradingConfig;
import io.edupilot.exam.ExamGradingProperties;

/** Boot-started mail slice. Recovery is performed only by the production @Scheduled method. */
public final class EmailOutboxScheduledRecoveryProbe {

	static final String MARKER = "local-mail-scheduled-recovery-v1";
	private static final Instant START = Instant.parse("2026-01-02T03:04:05Z");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static Path root;
	private static String phase;

	private EmailOutboxScheduledRecoveryProbe() { }

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 2 || !Set.of("seed", "normal", "paused", "without-scheduling").contains(arguments[0])) {
			throw new IllegalArgumentException("An owned fixture and supported phase are required");
		}
		phase = arguments[0];
		root = Path.of(arguments[1]).toRealPath();
		if (!Files.readString(root.resolve("owned-fixture.marker")).equals(MARKER)) {
			throw new IllegalArgumentException("Owned fixture marker mismatch");
		}
		Class<?> source = phase.equals("seed") || phase.equals("without-scheduling")
			? MailSlice.class : ScheduledMailSlice.class;
		var application = new SpringApplication(source);
		application.setWebApplicationType(WebApplicationType.NONE);
		try (var context = application.run("--spring.config.name=owned-mail-scheduled-fixture",
			"--spring.main.banner-mode=off", "--edupilot.mail.provider=logging",
			"--edupilot.mail.outbox.recovery-delay-ms=100", "--logging.level.root=ERROR")) {
			if (context.getBeansOfType(EmailSender.class).size() != 1
				|| !(context.getBean(EmailSender.class) instanceof JournalSender)
				|| !context.getBeansOfType(software.amazon.awssdk.services.sesv2.SesV2Client.class).isEmpty()
				|| !context.getBeansOfType(software.amazon.awssdk.services.sts.StsClient.class).isEmpty()) {
				throw new IllegalStateException("Only the synthetic journal provider is allowed");
			}
			// This slice imports exactly one @Scheduled method. Spring 7 Task wraps its
			// delegate in OutcomeTrackingRunnable, so inspecting the outer runnable's type
			// would incorrectly report zero registered methods.
			long scheduledMethods = context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).values().stream()
				.flatMap(processor -> processor.getScheduledTasks().stream())
				.count();
			if (phase.equals("seed")) {
				seed(context);
				write(context, "seed.json", scheduledMethods);
				Thread.sleep(Duration.ofSeconds(90)); // Parent forcibly stops this owned JVM after the committed barrier.
				throw new IllegalStateException("Owned producer was not interrupted");
			}
			if (phase.equals("without-scheduling")) {
				Thread.sleep(800);
			} else {
				if (scheduledMethods != 1) {
					throw new IllegalStateException("Production mail recovery must be registered exactly once");
				}
				long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
				while (!recovered(context)) {
					if (System.nanoTime() >= deadline) {
						throw new IllegalStateException("Automatic recovery did not reach the committed expected states");
					}
					Thread.sleep(25);
				}
				Thread.sleep(500); // Several additional scheduled ticks must not resend completed or UNKNOWN jobs.
			}
			write(context, "done.json", scheduledMethods);
		}
	}

	private static void seed(ConfigurableApplicationContext context) {
		context.getBean(EmailQuotaLockRepository.class).saveAndFlush(EmailQuotaLock.initial());
		JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
		EmailOutboxStore outbox = context.getBean(EmailOutboxStore.class);
		for (long id = 1; id <= 4; id++) {
			jdbc.update("insert into email_deliveries(id,recipient,type,status,subject,created_at,attempt_count) "
				+ "values(?,?,'PASSWORD_RESET','QUEUED',?,?,0)", id, "scheduled@example.test",
				"scheduled-fixture-" + id, Timestamp.from(START));
			outbox.enqueue(id, new EmailMessage("scheduled@example.test", "scheduled-fixture-" + id,
				"synthetic-scheduled-payload", null, EmailDeliveryType.PASSWORD_RESET), START.plusSeconds(id == 3 ? 60 : 1800));
		}
		for (long id : new long[] {2, 4}) {
			var claim = outbox.claim(id);
			if (claim == null || !context.getBean(EmailDeliveryStore.class).reserve(id, claim.token())) {
				throw new IllegalStateException("Owned claim/reservation could not commit");
			}
			if (id == 4 && !outbox.beginSending(claim)) {
				throw new IllegalStateException("Owned ambiguous-send boundary could not commit");
			}
		}
	}

	private static Map<String, String> states(ConfigurableApplicationContext context) {
		Map<String, String> values = new LinkedHashMap<>();
		context.getBean(JdbcTemplate.class).query("select delivery_id,status from email_outbox order by delivery_id",
			row -> { values.put(Long.toString(row.getLong(1)), row.getString(2)); });
		return values;
	}

	private static boolean recovered(ConfigurableApplicationContext context) {
		String dispatched = phase.equals("paused") ? "READY" : "SENT";
		return states(context).equals(Map.of("1", dispatched, "2", dispatched, "3", "FAILED", "4", "UNKNOWN"));
	}

	private static void write(ConfigurableApplicationContext context, String filename, long scheduledMethods) throws Exception {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("pid", ProcessHandle.current().pid());
		snapshot.put("phase", phase);
		snapshot.put("states", states(context));
		snapshot.put("registeredRecoveryTasks", scheduledMethods);
		snapshot.put("explicitRecoveryOrKickCalls", 0);
		snapshot.put("realSdkClients", 0);
		snapshot.put("webServerStarted", false);
		snapshot.put("provider", "synthetic-journal-only");
		Path staging = root.resolve(filename + ".tmp");
		Files.writeString(staging, JSON.writeValueAsString(snapshot));
		Files.move(staging, root.resolve(filename), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	@TestConfiguration(proxyBeanMethods = false)
	@Import({MailSlice.class, ExamGradingConfig.class})
	public static class ScheduledMailSlice { }

	@TestConfiguration(proxyBeanMethods = false)
	@EnableTransactionManagement
	@EnableJpaRepositories(basePackageClasses = EmailOutboxRepository.class)
	@Import({MailConfig.class, EmailOutboxWorker.class})
	public static class MailSlice {
		@Bean DataSource dataSource() {
			var source = new DriverManagerDataSource();
			source.setDriverClassName("org.h2.Driver");
			source.setUrl("jdbc:h2:file:" + root.resolve("mail-fixture").toString().replace('\\', '/')
				+ ";MODE=MySQL;WRITE_DELAY=0;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE");
			source.setUsername("sa");
			source.setPassword("");
			return source;
		}
		@Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
			var factory = new LocalContainerEntityManagerFactoryBean();
			factory.setDataSource(source);
			factory.setPackagesToScan("io.edupilot.mail");
			factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
			factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", phase.equals("seed") ? "create" : "validate",
				"hibernate.jdbc.time_zone", "UTC", "hibernate.show_sql", "false"));
			return factory;
		}
		@Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
		@Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
		@Bean Clock clock() { return Clock.fixed(phase.equals("seed") ? START : START.plusSeconds(180), ZoneOffset.UTC); }
		@Bean EmailOutboxProperties outboxProperties() { return new EmailOutboxProperties(null, Duration.ofMinutes(2), Duration.ofSeconds(1), 3, 50); }
		@Bean EmailDispatchProperties dispatchProperties() {
			return new EmailDispatchProperties(phase.equals("paused") ? EmailDispatchProperties.Mode.PAUSED
				: EmailDispatchProperties.Mode.NORMAL, Set.of(), "");
		}
		@Bean MailProperties mailProperties() { return new MailProperties(true, "synthetic-journal-only", "fixture@example.test", "", "http://127.0.0.1", "fixture"); }
		@Bean ExamGradingProperties gradingProperties() { return new ExamGradingProperties(Duration.ofMinutes(5), new ExamGradingProperties.Executor(1, 1, 1)); }
		@Bean EmailPayloadCipher payloadCipher(EmailOutboxProperties properties) {
			return new EmailPayloadCipher(properties, new JwtProperties("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", Duration.ofMinutes(5)), JSON);
		}
		@Bean EmailDeliveryStore deliveryStore(EmailDeliveryRepository deliveries, EmailQuotaLockRepository locks,
			EmailSendReservationRepository reservations, Clock clock) { return new EmailDeliveryStore(deliveries, locks, reservations, clock); }
		@Bean EmailOutboxStore outboxStore(EmailOutboxRepository jobs, EmailDeliveryRepository deliveries,
			EmailPayloadCipher cipher, EmailOutboxProperties properties, EmailDispatchProperties dispatch,
			Clock clock, EntityManagerFactory factory) {
			return new EmailOutboxStore(jobs, deliveries, cipher, properties, dispatch, clock, SharedEntityManagerCreator.createSharedEntityManager(factory));
		}
		@Bean EmailSender syntheticSender() { return new JournalSender(); }
	}

	private static final class JournalSender implements EmailSender {
		@Override public synchronized EmailDeliveryResult send(EmailMessage message) {
			if (!message.to().equals("scheduled@example.test") || !message.textBody().equals("synthetic-scheduled-payload")
				|| !message.subject().matches("scheduled-fixture-[1-4]")) {
				throw new IllegalStateException("Only synthetic fixture mail can reach this sender");
			}
			String id = message.subject().substring("scheduled-fixture-".length());
			try {
				Files.writeString(root.resolve("fake-provider-journal.csv"), id + "," + ProcessHandle.current().pid() + "\n",
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (Exception error) {
				throw new IllegalStateException("Synthetic journal unavailable", error);
			}
			return new EmailDeliveryResult("synthetic-scheduled-" + id);
		}
	}
}
