package io.edupilot.exam;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sts.StsClient;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.edupilot.MainServiceApplication;
import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.guardian.GuardianConsentFence;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

/** Standalone test helper; deliberately not another SpringBootConfiguration. */
public final class ExamGradingProcessRecoveryProbe {

	private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
	private static Controls controls;

	private ExamGradingProcessRecoveryProbe() { }

	public static void main(String[] args) throws Exception {
		if (args.length != 4) throw new IllegalArgumentException("Explicit private root/owner/mode/scenario required");
		Path root = Path.of(args[0]).toRealPath();
		if (!Files.readString(root.resolve(".owned-grading-probe")).equals(args[1])
			|| root.toString().contains(";")) throw new IllegalArgumentException("Unowned fixture root");
		String mode = args[2];
		String scenario = args[3];
		if (!List.of("seed", "recover").contains(mode) || !List.of("saturated", "claimed", "cutoff").contains(scenario)) {
			throw new IllegalArgumentException("Unsupported synthetic scenario");
		}
		controls = new Controls(mode.equals("seed") && scenario.equals("claimed"));
		try {
			SpringApplication app = new SpringApplication(MainServiceApplication.class, ProbeConfiguration.class);
			app.setWebApplicationType(WebApplicationType.SERVLET);
			app.setLogStartupInfo(false);
			String testOrigin = ExamGradingProcessRecoveryProbe.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
			app.addInitializers(context -> context.getBeanFactory().registerSingleton("processRecoveryClasspathFilter", new ProbeClasspathFilter(testOrigin)));
			try (ConfigurableApplicationContext context = app.run(arguments(root, mode))) {
				require(context.getBeansOfType(SesV2Client.class).isEmpty(), "No SES SDK client constructed");
				require(context.getBeansOfType(StsClient.class).isEmpty(), "No identity SDK client constructed");
				require(context.getBeansOfType(AiClient.class).size() == 1, "Only the local AI stub is available");
				require(context.getBean(ProbeClasspathFilter.class).excludedTestClasses.get() > 0, "Test classpath excluded from application scan");
				if (mode.equals("seed")) {
					seed(context, root, scenario);
					// The parent must physically terminate this PID. No graceful context close occurs here.
					if (new CountDownLatch(1).await(120, TimeUnit.SECONDS)) throw new AssertionError("Unexpected crash barrier release");
					throw new AssertionError("Parent did not kill the seed process");
				}
				recover(context, root, scenario);
			}
			System.exit(0);
		} catch (Throwable failure) {
			Properties evidence = new Properties();
			evidence.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
			evidence.setProperty("failureType", failure.getClass().getName());
			evidence.setProperty("failure", String.valueOf(failure.getMessage()));
			evidence.setProperty("result", "FAIL");
			write(root.resolve(mode + "-failure.properties"), evidence);
			failure.printStackTrace(System.err);
			System.exit(1);
		}
	}

	private static String[] arguments(Path root, String mode) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("spring.profiles.active", "jpa-context");
		values.put("spring.main.banner-mode", "off");
		values.put("spring.datasource.url", "jdbc:h2:file:" + root.resolve("grading").toString().replace('\\', '/')
			+ ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;LOCK_TIMEOUT=5000");
		values.put("spring.datasource.driver-class-name", "org.h2.Driver");
		values.put("spring.datasource.username", "sa");
		values.put("spring.datasource.password", "");
		values.put("spring.datasource.hikari.maximum-pool-size", "4");
		values.put("spring.flyway.enabled", "false");
		values.put("spring.jpa.hibernate.ddl-auto", mode.equals("seed") ? "create" : "validate");
		values.put("spring.jpa.open-in-view", "false");
		values.put("server.address", "127.0.0.1");
		values.put("server.port", "0");
		values.put("server.tomcat.basedir", root.resolve("tomcat").toString());
		values.put("logging.level.root", "WARN");
		values.put("edupilot.cors.allowed-origins", "http://127.0.0.1");
		values.put("edupilot.ai.base-url", "http://127.0.0.1:9");
		values.put("edupilot.ai.internal-token", "synthetic-process-only");
		values.put("edupilot.jwt.secret", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
		values.put("edupilot.storage.root-directory", root.resolve("storage").toString());
		values.put("edupilot.mail.enabled", "false");
		values.put("edupilot.mail.provider", "logging");
		values.put("edupilot.mail.outbox.dispatch.mode", "PAUSED");
		values.put("edupilot.admin.infra.enabled", "false");
		values.put("edupilot.admin.xai.management-api-key", "");
		values.put("edupilot.deletion.enabled", "false");
		values.put("edupilot.material.xai-files.backfill.enabled", "false");
		values.put("edupilot.exam.grading.executor.core-size", "1");
		values.put("edupilot.exam.grading.executor.max-size", "1");
		values.put("edupilot.exam.grading.executor.queue-capacity", "1");
		values.put("edupilot.exam.grading.lease-duration", "PT5M");
		return values.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
	}

	private static void seed(ConfigurableApplicationContext context, Path root, String scenario) throws Exception {
		require(!context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME),
			"No automatic scheduled jobs in a process probe");
		ThreadPoolTaskExecutor pool = context.getBean("examGradingExecutor", ThreadPoolTaskExecutor.class);
		if (!scenario.equals("claimed")) {
			CountDownLatch occupied = new CountDownLatch(1);
			pool.execute(() -> {
				occupied.countDown();
				try {
					if (!new CountDownLatch(1).await(120, TimeUnit.SECONDS)) throw new AssertionError("Crash barrier timed out");
				} catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			});
			require(occupied.await(5, TimeUnit.SECONDS), "Real executor worker occupied");
			pool.execute(() -> { });
			require(pool.getThreadPoolExecutor().getQueue().remainingCapacity() == 0, "Real bounded queue saturated");
		}
		Properties fixture = new Properties();
		long main = createSubmission(context, "main");
		fixture.setProperty("main", Long.toString(main));
		JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
		if (scenario.equals("claimed")) {
			require(controls.entered.await(15, TimeUnit.SECONDS), "Real worker reached the blocking local AI stub");
		} else {
			jdbc.update("update exam_submissions set updated_at = ? where id = ?", Timestamp.from(NOW), main);
			require(controls.calls.get() == 0, "Rejected pre-AI dispatch must not invoke the stub");
		}
		if (scenario.equals("cutoff")) {
			jdbc.update("update exam_submissions set grading_retry_count = 2, updated_at = ? where id = ?",
				Timestamp.from(NOW.minusSeconds(31 * 60)), main);
			long recent = createSubmission(context, "recent");
			long expired = createSubmission(context, "expired");
			fixture.setProperty("recent", Long.toString(recent));
			fixture.setProperty("expired", Long.toString(expired));
			jdbc.update("update exam_submissions set updated_at = ? where id = ?", Timestamp.from(NOW.minusSeconds(29 * 60)), recent);
			jdbc.update("update exam_submissions set updated_at = ? where id = ?", Timestamp.from(NOW.minusSeconds(31 * 60)), expired);
		}
		write(root.resolve("fixture.properties"), fixture);
		Properties before = snapshot(context, fixture);
		before.setProperty("scheduledJobsDisabled", "true");
		before.setProperty("queueSize", Integer.toString(pool.getThreadPoolExecutor().getQueue().size()));
		before.setProperty("activeWorkers", Integer.toString(pool.getActiveCount()));
		require(before.getProperty("main.status").equals("SUBMITTED"), "Committed pending submission");
		require(before.getProperty("main.leasePresent").equals(Boolean.toString(scenario.equals("claimed"))), "Expected lease barrier");
		jdbc.execute("CHECKPOINT SYNC");
		write(root.resolve("seed.properties"), before);
	}

	private static long createSubmission(ConfigurableApplicationContext context, String suffix) {
		UserRepository users = context.getBean(UserRepository.class);
		User instructor = adult("instructor-" + suffix, UserRole.INSTRUCTOR);
		User learner = adult("learner-" + suffix, UserRole.LEARNER);
		instructor = users.saveAndFlush(instructor);
		learner = users.saveAndFlush(learner);
		Classroom classroom = context.getBean(ClassroomRepository.class).saveAndFlush(Classroom.create(instructor,
			"Private synthetic process classroom", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE, null, "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)));
		context.getBean(ClassroomMemberRepository.class).saveAndFlush(ClassroomMember.create(classroom, learner, NOW.minusSeconds(60)));
		Exam exam = Exam.create(classroom, 1, "Private synthetic process exam", null, false);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		exam.publish(NOW.minusSeconds(60));
		exam = context.getBean(ExamRepository.class).saveAndFlush(exam);
		context.getBean(ExamQuestionRepository.class).saveAndFlush(ExamQuestion.create(exam, 1, ExamQuestionType.SHORT,
			new BigDecimal("10.00"), new ExamPublicQuestion("Synthetic question", List.of()),
			new ExamPrivateAnswer(null, null, null, "Synthetic reference", null, List.of()), "1.0"));
		return context.getBean(StudentExamService.class).submit(learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest("process-" + suffix, List.of(new ExamAnswerRequest("q1", "Fixed synthetic process answer")))).submissionId();
	}

	private static User adult(String localPart, UserRole role) {
		User user = User.create(localPart + "@example.test", "!synthetic-unusable", "Synthetic process actor", role);
		user.recordSignupDateOfBirth(LocalDate.of(1990, 1, 1));
		user.verifyEmail(NOW.minusSeconds(60));
		return user;
	}

	private static void recover(ConfigurableApplicationContext context, Path root, String scenario) throws Exception {
		Properties fixture = new Properties();
		try (var in = Files.newInputStream(root.resolve("fixture.properties"))) { fixture.load(in); }
		ExamSubmissionPersistenceService persistence = context.getBean(ExamSubmissionPersistenceService.class);
		ExamGradingRecoveryScheduler scheduler = context.getBean(ExamGradingRecoveryScheduler.class);
		JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
		MutableClock clock = context.getBean(MutableClock.class);
		long main = Long.parseLong(fixture.getProperty("main"));
		String oldLease = null;
		Properties checks = new Properties();
		if (scenario.equals("claimed")) {
			oldLease = jdbc.queryForObject("select grading_lease_token from exam_submissions where id = ?", String.class, main);
			clock.now.set(NOW.plusSeconds(299));
			scheduler.recover();
			boolean premature = persistence.claimGradingLease(main, "synthetic-premature", clock.instant(), clock.instant().plusSeconds(300));
			require(!premature && controls.calls.get() == 0, "Live predecessor lease remains fenced before its expiry");
			checks.setProperty("beforeExpiryClaim", Boolean.toString(premature));
			checks.setProperty("beforeExpiryAiCalls", Integer.toString(controls.calls.get()));
			clock.now.set(NOW.plusSeconds(301));
		}
		scheduler.recover();
		await(() -> jdbc.queryForObject("select count(*) from exam_submissions where status = 'SUBMITTED'", Integer.class) == 0,
			Duration.ofSeconds(15), "Real recovery worker reaches terminal persistence");
		await(() -> jdbc.queryForObject("select count(*) from notifications where type = 'EXAM_GRADED'", Integer.class)
				== (scenario.equals("cutoff") ? 2 : 1),
			Duration.ofSeconds(10), "After-commit deduplicated notifications persisted");
		if (scenario.equals("claimed")) {
			long learner = jdbc.queryForObject("select user_id from exam_submissions where id = ?", Long.class, main);
			boolean stale = persistence.applyAiGrading(main, oldLease, new ExamAiGradingOutcome(Map.of("q1",
				new ExamAiGradingOutcome.GradedItem(BigDecimal.ZERO, Verdict.WRONG, "Synthetic stale result")), false,
				context.getBean(GuardianConsentFence.class).capture(learner)));
			require(!stale, "Old worker result must not overwrite a replacement result");
			checks.setProperty("staleResultApplied", Boolean.toString(stale));
		}
		int beforeRepeat = controls.calls.get();
		scheduler.recover();
		// A second genuine worker call after a terminal result cannot claim or invoke AI.
		context.getBean(ExamGradingWorker.class).grade(main);
		require(controls.calls.get() == beforeRepeat, "Repeat recovery does not apply a second grade");
		Properties after = snapshot(context, fixture);
		require(after.getProperty("main.status").equals(scenario.equals("cutoff") ? "GRADING_FAILED" : "GRADED"), "Expected terminal contract");
		if (!scenario.equals("cutoff")) require(after.getProperty("main.score").equals("8.00"), "Replacement score remains intact");
		after.putAll(checks);
		after.setProperty("repeatRecoveryAiCalls", Integer.toString(controls.calls.get()));
		after.setProperty("result", "PASS");
		write(root.resolve("recover.properties"), after);
	}

	private static Properties snapshot(ConfigurableApplicationContext context, Properties fixture) throws Exception {
		Properties result = new Properties();
		result.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
		result.setProperty("aiCalls", Integer.toString(controls.calls.get()));
		result.setProperty("serverPort", context.getEnvironment().getRequiredProperty("local.server.port"));
		result.setProperty("excludedTestClasspathClasses", Integer.toString(context.getBean(ProbeClasspathFilter.class).excludedTestClasses.get()));
		result.setProperty("sesSdkClients", Integer.toString(context.getBeansOfType(SesV2Client.class).size()));
		result.setProperty("stsSdkClients", Integer.toString(context.getBeansOfType(StsClient.class).size()));
		result.setProperty("aiClients", Integer.toString(context.getBeansOfType(AiClient.class).size()));
		JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
		result.setProperty("notifications", jdbc.queryForObject("select count(*) from notifications where type = 'EXAM_GRADED'", Integer.class).toString());
		result.setProperty("usageRows", jdbc.queryForObject("select count(*) from ai_usage_log", Integer.class).toString());
		result.setProperty("knownCostRows", jdbc.queryForObject("select count(*) from ai_usage_log where cost_usd_ticks is not null", Integer.class).toString());
		for (String name : fixture.stringPropertyNames()) {
			long id = Long.parseLong(fixture.getProperty(name));
			Map<String, Object> row = jdbc.queryForMap("select id, exam_id, user_id, request_id, status, score, grading_retry_count, "
				+ "grading_lease_token, grading_lease_until from exam_submissions where id = ?", id);
			Map<String, Object> answer = jdbc.queryForMap("select id, answer from exam_answers where submission_id = ?", id);
			result.setProperty(name + ".submissionId", row.get("ID").toString());
			result.setProperty(name + ".examId", row.get("EXAM_ID").toString());
			result.setProperty(name + ".userId", row.get("USER_ID").toString());
			result.setProperty(name + ".answerId", answer.get("ID").toString());
			result.setProperty(name + ".requestHash", hash(row.get("REQUEST_ID").toString()));
			result.setProperty(name + ".answerHash", hash(answer.get("ANSWER").toString()));
			result.setProperty(name + ".status", row.get("STATUS").toString());
			result.setProperty(name + ".score", String.valueOf(row.get("SCORE")));
			result.setProperty(name + ".retryCount", row.get("GRADING_RETRY_COUNT").toString());
			result.setProperty(name + ".leasePresent", Boolean.toString(row.get("GRADING_LEASE_TOKEN") != null));
			boolean epoch = jdbc.queryForObject("select count(*) from exam_submissions where id = ? and grading_lease_until = ?",
				Integer.class, id, Timestamp.from(Instant.EPOCH)) == 1;
			result.setProperty(name + ".leaseEpoch", Boolean.toString(epoch));
		}
		return result;
	}

	private static String hash(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private static void await(BooleanSupplier condition, Duration timeout, String assertion) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
		require(condition.getAsBoolean(), assertion);
	}

	private static void require(boolean condition, String assertion) {
		if (!condition) throw new AssertionError(assertion);
	}

	private static void write(Path path, Properties values) throws Exception {
		Path temporary = path.resolveSibling(path.getFileName() + ".writing");
		try (var out = Files.newOutputStream(temporary)) { values.store(out, "synthetic grading process acceptance"); }
		Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeConfiguration {
		@Bean @Primary MutableClock processRecoveryClock() { return new MutableClock(); }
		@Bean @Primary AiClient processRecoveryAi() {
			return (AiClient) Proxy.newProxyInstance(AiClient.class.getClassLoader(), new Class<?>[] { AiClient.class },
				(proxy, method, args) -> {
					if (method.getName().equals("toString")) return "LocalGradingProcessAiStub";
					if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
					if (method.getName().equals("equals")) return proxy == args[0];
					if (!method.getName().equals("grade")) throw new AssertionError("Unexpected AI operation: " + method.getName());
					controls.calls.incrementAndGet();
					controls.entered.countDown();
					if (controls.block && !new CountDownLatch(1).await(120, TimeUnit.SECONDS)) {
						throw new AssertionError("Parent did not kill the AI-blocked process");
					}
					GradeRequest request = (GradeRequest) args[0];
					return new GradeResponse("1.0", request.quizId(), "SHORT", new BigDecimal("8.00"), BigDecimal.TEN,
						List.of(new GradeResponse.Item("q1", new BigDecimal("8.00"), BigDecimal.TEN, "PARTIAL", "Synthetic feedback")), null);
				});
		}
		@Bean static BeanFactoryPostProcessor disableAutomaticSchedulingForProbeOnly() {
			return beanFactory -> {
				BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
				if (registry.containsBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
					registry.removeBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
				}
			};
		}
	}

	/** Standalone SpringApplication lacks the SpringBootTest bootstrapper's test exclusion. */
	static final class ProbeClasspathFilter extends TypeExcludeFilter {
		private final String testOrigin;
		private final AtomicInteger excludedTestClasses = new AtomicInteger();
		private ProbeClasspathFilter(String testOrigin) { this.testOrigin = testOrigin; }
		@Override public boolean match(MetadataReader metadata, MetadataReaderFactory factory) throws java.io.IOException {
			if (metadata.getClassMetadata().getClassName().equals("io.edupilot.ai.HttpAiClient")) return true;
			boolean testOnly = metadata.getResource().getURL().toExternalForm().startsWith(testOrigin);
			if (testOnly) excludedTestClasses.incrementAndGet();
			return testOnly;
		}
		@Override public boolean equals(Object other) {
			return other instanceof ProbeClasspathFilter filter && testOrigin.equals(filter.testOrigin);
		}
		@Override public int hashCode() { return testOrigin.hashCode(); }
	}

	static final class MutableClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
		@Override public Instant instant() { return now.get(); }
	}

	private static final class Controls {
		private final AtomicInteger calls = new AtomicInteger();
		private final CountDownLatch entered = new CountDownLatch(1);
		private final boolean block;
		private Controls(boolean block) { this.block = block; }
	}
}
