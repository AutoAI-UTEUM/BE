package io.edupilot.mail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

import io.edupilot.auth.JwtProperties;

/**
 * Test-only OS-process probe. No application scan, Boot auto-configuration, scheduling,
 * network client, SES factory, operating configuration, or credential chain is started.
 */
public final class EmailOutboxProcessRecoveryProbe {
	static final Instant START = Instant.parse("2026-01-02T03:04:05Z");
	static final String RECIPIENT = "mail-process@example.test";
	private static final String BODY = "synthetic-process-payload";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static Settings settings;
	private static AnnotationConfigApplicationContext context;

	private EmailOutboxProcessRecoveryProbe() { }

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 2) {
			throw new IllegalArgumentException("Only a phase and owned fixture directory are accepted");
		}
		Path root = Path.of(arguments[1]).toRealPath();
		if (!Files.readString(root.resolve("owned-fixture.marker")).equals("local-mail-process-fixture-v1")) {
			throw new IllegalArgumentException("Owned fixture marker is required");
		}
		settings = Settings.forPhase(arguments[0], root);
		try (var application = new AnnotationConfigApplicationContext(ProbeConfiguration.class)) {
			context = application;
			if (application.getBeansOfType(EmailSender.class).size() != 1
				|| !(application.getBean(EmailSender.class) instanceof JournalSender)) {
				throw new IllegalStateException("Only the fake journal sender may be constructed");
			}
			if (settings.seed()) {
				application.getBean(EmailQuotaLockRepository.class).saveAndFlush(EmailQuotaLock.initial());
			}
			switch (settings.phase()) {
				case "prepare-ready-and-claim" -> {
					queue(1, RECIPIENT, RECIPIENT, START.plusSeconds(1800));
					queue(2, RECIPIENT, RECIPIENT, START.plusSeconds(1800));
					var claim = outbox().claim(2L);
					if (claim == null || !application.getBean(EmailDeliveryStore.class).reserve(2L, claim.token())) {
						throw new IllegalStateException("Pre-send claim/reservation did not commit");
					}
					barrier();
				}
				case "recover-ready-and-claim", "recover-unknown" -> {
					sweepAndKick(2);
					finish();
				}
				case "prepare-accepted-send" -> {
					queue(1, RECIPIENT, RECIPIENT, START.plusSeconds(1800));
					worker().kick(1L); // The fake blocks after its durable accepted-call record.
					throw new IllegalStateException("Accepted-send interruption barrier was bypassed");
				}
				case "prepare-paused" -> {
					queue(1, RECIPIENT, RECIPIENT, START.plusSeconds(1800));
					queue(2, RECIPIENT, RECIPIENT, START.plusSeconds(1800));
					queue(3, RECIPIENT, "wrong-payload@example.test", START.plusSeconds(1800));
					queue(4, RECIPIENT, RECIPIENT, START.plusSeconds(1800));
					sweepAndKick(4);
					barrier();
				}
				case "isolated-first", "isolated-second", "isolated-expiry" -> {
					sweepAndKick(4);
					finish();
				}
				case "prepare-expiry-and-quota" -> {
					queue(1, RECIPIENT, RECIPIENT, START.plusSeconds(1));
					queue(2, RECIPIENT, RECIPIENT, START.plusSeconds(1800));
					application.getBean(JdbcTemplate.class).update(
						"update email_outbox set encrypted_payload=? where delivery_id=2", new byte[] {1, 2, 3});
					for (long id = 3; id <= 8; id++) {
						queue(id, RECIPIENT, RECIPIENT, START.plusSeconds(1800));
					}
					barrier();
				}
				case "recover-expiry-and-quota" -> {
					sweepAndKick(8);
					finish();
				}
				default -> throw new IllegalArgumentException("Unsupported local process phase");
			}
		}
	}

	private static EmailOutboxStore outbox() { return context.getBean(EmailOutboxStore.class); }
	private static EmailOutboxWorker worker() { return context.getBean(EmailOutboxWorker.class); }

	private static void queue(long id, String historyRecipient, String payloadRecipient, Instant expiry) {
		context.getBean(JdbcTemplate.class).update(
			"insert into email_deliveries(id,recipient,type,status,subject,created_at,attempt_count) "
				+ "values(?,?,?,'QUEUED',?,?,0)", id, historyRecipient, EmailDeliveryType.PASSWORD_RESET.name(),
			"mail-process-id-" + id, java.sql.Timestamp.from(START));
		outbox().enqueue(id, new EmailMessage(payloadRecipient, "mail-process-id-" + id, BODY, null,
			EmailDeliveryType.PASSWORD_RESET), expiry);
	}

	private static void sweepAndKick(int highestId) {
		for (int round = 0; round < 3; round++) {
			worker().recoverPending();
			for (long id = 1; id <= highestId; id++) {
				worker().kick(id);
			}
		}
	}

	private static void barrier() throws Exception {
		writeSnapshot("barrier");
		if (new CountDownLatch(1).await(90, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Unexpected local interruption barrier release");
		}
		throw new IllegalStateException("Parent did not terminate its owned child before deadline");
	}

	private static void finish() throws IOException { writeSnapshot("done"); }

	private static void writeSnapshot(String suffix) throws IOException {
		JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("phase", settings.phase());
		result.put("pid", ProcessHandle.current().pid());
		result.put("databaseKind", "owned-file-h2");
		result.put("clock", settings.now().toString());
		result.put("dispatchMode", settings.mode().name());
		result.put("senderClass", JournalSender.class.getName());
		result.put("externalProviderConstructed", false);
		result.put("rows", jdbc.query("select o.delivery_id,o.status,d.status,o.attempt_count,d.attempt_count,"
			+ "o.encrypted_payload,o.lease_until,o.last_error_code,d.error_summary "
			+ "from email_outbox o join email_deliveries d on d.id=o.delivery_id order by o.delivery_id", (rs, index) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			long id = rs.getLong(1);
			row.put("id", id);
			row.put("outboxStatus", rs.getString(2));
			row.put("deliveryStatus", rs.getString(3));
			row.put("outboxAttempts", rs.getInt(4));
			row.put("deliveryAttempts", rs.getInt(5));
			byte[] payload = rs.getBytes(6);
			row.put("payloadPresent", payload != null);
			if (payload != null && payload.length > 16) {
				String persisted = new String(payload, StandardCharsets.ISO_8859_1);
				row.put("payloadProtected", !persisted.contains(BODY) && !persisted.contains(RECIPIENT));
				boolean bound = false;
				try {
					context.getBean(EmailPayloadCipher.class).decrypt(id + 1000, payload);
				} catch (IllegalStateException expected) {
					bound = true;
				}
				row.put("cipherBindsDeliveryId", bound);
			}
			row.put("leasePresent", rs.getObject(7) != null);
			row.put("errorCode", rs.getString(8));
			row.put("historyError", rs.getString(9));
			row.put("reservedUnits", jdbc.queryForObject(
				"select coalesce(sum(units),0) from email_send_reservations where delivery_id=?", Long.class, id));
			return row;
		}));
		result.put("reservedUnits", jdbc.queryForObject("select coalesce(sum(units),0) from email_send_reservations", Long.class));
		byte[] bytes = JSON.writeValueAsBytes(result);
		Path file = settings.root().resolve(settings.phase() + "." + suffix + ".json");
		Path pending = file.resolveSibling(file.getFileName() + ".pending");
		try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) { channel.write(buffer); }
			channel.force(true);
		}
		Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE);
	}

	private record Settings(String phase, Path root, boolean seed, Instant now, EmailDispatchProperties.Mode mode) {
		static Settings forPhase(String phase, Path root) {
			boolean seed = phase.startsWith("prepare-");
			Instant now = seed ? START : START.plusSeconds(121);
			if (phase.equals("isolated-second")) { now = START.plusSeconds(150); }
			if (phase.equals("isolated-expiry")) { now = START.plusSeconds(1801); }
			EmailDispatchProperties.Mode mode = phase.equals("prepare-paused") || phase.equals("isolated-expiry")
				? EmailDispatchProperties.Mode.PAUSED
				: phase.startsWith("isolated-") ? EmailDispatchProperties.Mode.ISOLATED_TRIAL
				: EmailDispatchProperties.Mode.NORMAL;
			return new Settings(phase, root, seed, now, mode);
		}
	}

	/** Excluded by Boot's normal component scan; used only by the explicit probe context. */
	@TestConfiguration(proxyBeanMethods = false)
	@EnableTransactionManagement
	@EnableJpaRepositories(basePackageClasses = EmailOutboxRepository.class)
	public static class ProbeConfiguration {
		@Bean DataSource dataSource() {
			var source = new DriverManagerDataSource();
			source.setDriverClassName("org.h2.Driver");
			source.setUrl("jdbc:h2:file:" + settings.root().resolve("mail-fixture").toString().replace('\\', '/')
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
			factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", settings.seed() ? "create" : "validate",
				"hibernate.jdbc.time_zone", "UTC", "hibernate.show_sql", "false"));
			return factory;
		}
		@Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
			return new JpaTransactionManager(factory);
		}
		@Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
		@Bean Clock clock() { return Clock.fixed(settings.now(), ZoneOffset.UTC); }
		@Bean EmailOutboxProperties outboxProperties() {
			return new EmailOutboxProperties(null, Duration.ofMinutes(2), Duration.ofSeconds(1), 3, 50);
		}
		@Bean EmailDispatchProperties dispatchProperties() {
			return settings.mode() == EmailDispatchProperties.Mode.ISOLATED_TRIAL
				? new EmailDispatchProperties(settings.mode(), Set.of(1L, 2L, 3L), RECIPIENT)
				: new EmailDispatchProperties(settings.mode(), Set.of(), "");
		}
		@Bean MailProperties mailProperties() {
			return new MailProperties(true, "fake-process-only", "fixture@example.test", "", "http://127.0.0.1", "fixture");
		}
		@Bean EmailPayloadCipher payloadCipher(EmailOutboxProperties properties) {
			// A fixed synthetic fixture key; never reads any user/operating signing key.
			return new EmailPayloadCipher(properties,
				new JwtProperties("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", Duration.ofMinutes(5)), JSON);
		}
		@Bean EmailDeliveryStore deliveryStore(EmailDeliveryRepository deliveries, EmailQuotaLockRepository locks,
			EmailSendReservationRepository reservations, Clock clock) {
			return new EmailDeliveryStore(deliveries, locks, reservations, clock);
		}
		@Bean EmailOutboxStore outboxStore(EmailOutboxRepository jobs, EmailDeliveryRepository deliveries,
			EmailPayloadCipher cipher, EmailOutboxProperties properties, EmailDispatchProperties dispatch,
			Clock clock, EntityManagerFactory factory) {
			return new EmailOutboxStore(jobs, deliveries, cipher, properties, dispatch, clock,
				SharedEntityManagerCreator.createSharedEntityManager(factory));
		}
		@Bean EmailSender fakeSender() { return new JournalSender(); }
		@Bean EmailOutboxWorker worker(EmailOutboxStore outbox, EmailDeliveryStore history, EmailSender sender,
			MailProperties properties) {
			return new EmailOutboxWorker(outbox, history, sender, properties, Runnable::run);
		}
	}

	private static final class JournalSender implements EmailSender {
		@Override public EmailDeliveryResult send(EmailMessage message) {
			if (!message.to().endsWith("@example.test") || !BODY.equals(message.textBody())
				|| !message.subject().matches("mail-process-id-[1-8]")) {
				throw new IllegalStateException("Only synthetic fixture mail can reach the fake sender");
			}
			long id = Long.parseLong(message.subject().substring("mail-process-id-".length()));
			String outcome = settings.phase().startsWith("isolated-") && id == 2 ? "THROTTLED" : "ACCEPTED";
			String line = id + "," + ProcessHandle.current().pid() + "," + outcome + "\n";
			try (var channel = FileChannel.open(settings.root().resolve("fake-provider-journal.csv"),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
				ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
				while (buffer.hasRemaining()) { channel.write(buffer); }
				channel.force(true);
			} catch (IOException failure) {
				throw new IllegalStateException("Synthetic fake journal unavailable");
			}
			if (settings.phase().equals("prepare-accepted-send")) {
				try { barrier(); } catch (Exception failure) { throw new IllegalStateException("Synthetic interruption failed"); }
			}
			if (outcome.equals("THROTTLED")) { throw new EmailSendRejection(true); }
			return new EmailDeliveryResult("synthetic-process-receipt-" + id);
		}
	}
}
