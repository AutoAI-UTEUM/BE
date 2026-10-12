package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Actual JVM termination/replacement with production persistence and dispatch code, fake provider only. */
class EmailOutboxProcessRecoveryTest {
	private static final ObjectMapper JSON = new ObjectMapper();
	@TempDir Path temporary;

	@Test
	void readyAndReservedPreSendClaimSurviveForcedJvmStopAndRecoverExactlyOnce() throws Exception {
		try (var fixture = fixture("ready-claim")) {
			var producer = fixture.start("prepare-ready-and-claim");
			var before = fixture.awaitSnapshot(producer, "barrier");
			assertState(before, 1, "READY", "QUEUED", 0);
			assertState(before, 2, "CLAIMED", "QUEUED", 0);
			assertThat(row(before, 2).get("reservedUnits")).isEqualTo(1);
			for (long id = 1; id <= 2; id++) {
				assertThat(row(before, id).get("payloadProtected")).isEqualTo(true);
				assertThat(row(before, id).get("cipherBindsDeliveryId")).isEqualTo(true);
			}
			assertThat(fixture.journal()).isEmpty();
			fixture.kill(producer);
			var consumer = fixture.start("recover-ready-and-claim");
			var after = fixture.finished(consumer);
			fixture.assertReplacement(producer, consumer);
			for (long id = 1; id <= 2; id++) {
				assertState(after, id, "SENT", "SENT", 1);
				assertRemovedPayloadAndLease(after, id);
			}
			assertThat(row(after, 1).get("reservedUnits")).isEqualTo(1);
			assertThat(row(after, 2).get("reservedUnits")).isEqualTo(2);
			// The interrupted reservation is retained; replacement claims consume fresh quota.
			assertThat(after.get("reservedUnits")).isEqualTo(3);
			assertFakeCalls(fixture, 1, 1);
			assertFakeCalls(fixture, 2, 1);
			fixture.passed();
		}
	}

	@Test
	void fakeProviderAcceptedSendingSurvivesForcedStopAsUnknownWithoutBlindResend() throws Exception {
		try (var fixture = fixture("accepted-unknown")) {
			var producer = fixture.start("prepare-accepted-send");
			var before = fixture.awaitSnapshot(producer, "barrier");
			assertState(before, 1, "SENDING", "QUEUED", 1);
			assertFakeCalls(fixture, 1, 1);
			assertThat(fixture.journal().getFirst()).endsWith(",ACCEPTED");
			fixture.kill(producer);
			var consumer = fixture.start("recover-unknown");
			var after = fixture.finished(consumer);
			fixture.assertReplacement(producer, consumer);
			assertState(after, 1, "UNKNOWN", "FAILED", 1);
			assertThat(row(after, 1).get("errorCode")).isEqualTo("DELIVERY_RESULT_UNKNOWN");
			assertRemovedPayloadAndLease(after, 1);
			assertThat(after.get("reservedUnits")).isEqualTo(1);
			assertFakeCalls(fixture, 1, 1);
			fixture.passed();
		}
	}

	@Test
	void pausedAndExactIdTrialKeepSingleAttemptAcrossReplacementJvmsAndExpireHeldPayloads() throws Exception {
		try (var fixture = fixture("paused-isolation")) {
			var paused = fixture.start("prepare-paused");
			var before = fixture.awaitSnapshot(paused, "barrier");
			assertThat(before.get("dispatchMode")).isEqualTo("PAUSED");
			for (long id = 1; id <= 4; id++) { assertState(before, id, "READY", "QUEUED", 0); }
			assertThat(fixture.journal()).isEmpty();
			assertThat(before.get("reservedUnits")).isEqualTo(0);
			fixture.kill(paused);

			var first = fixture.start("isolated-first");
			var firstResult = fixture.finished(first);
			fixture.assertReplacement(paused, first);
			assertState(firstResult, 1, "SENT", "SENT", 1);
			assertState(firstResult, 2, "RETRY", "QUEUED", 1);
			assertState(firstResult, 3, "READY", "QUEUED", 0); // Approved ID, wrong encrypted recipient.
			assertState(firstResult, 4, "READY", "QUEUED", 0); // Correct recipient, unapproved ID.

			var second = fixture.start("isolated-second");
			var after = fixture.finished(second);
			fixture.assertReplacement(first, second);
			assertState(after, 1, "SENT", "SENT", 1);
			assertState(after, 2, "RETRY", "QUEUED", 1);
			assertState(after, 3, "READY", "QUEUED", 0);
			assertState(after, 4, "READY", "QUEUED", 0);
			assertFakeCalls(fixture, 1, 1);
			assertFakeCalls(fixture, 2, 1);
			assertFakeCalls(fixture, 3, 0);
			assertFakeCalls(fixture, 4, 0);
			assertThat(after.get("reservedUnits")).isEqualTo(2);

			var expiry = fixture.start("isolated-expiry");
			var expired = fixture.finished(expiry);
			fixture.assertReplacement(second, expiry);
			assertThat(expired.get("dispatchMode")).isEqualTo("PAUSED");
			assertState(expired, 1, "SENT", "SENT", 1);
			for (long id = 2; id <= 4; id++) {
				assertThat(row(expired, id).get("errorCode")).isEqualTo("PAYLOAD_EXPIRED");
				assertRemovedPayloadAndLease(expired, id);
			}
			assertThat(fixture.journal()).hasSize(2);
			fixture.passed();
		}
	}

	@Test
	void expiryCipherFailureAndDispatchTimeRecipientQuotaRemainEnforcedAfterJvmRestart() throws Exception {
		try (var fixture = fixture("expiry-quota")) {
			var producer = fixture.start("prepare-expiry-and-quota");
			fixture.awaitSnapshot(producer, "barrier");
			assertThat(fixture.journal()).isEmpty();
			fixture.kill(producer);
			var consumer = fixture.start("recover-expiry-and-quota");
			var after = fixture.finished(consumer);
			fixture.assertReplacement(producer, consumer);
			assertState(after, 1, "FAILED", "FAILED", 0);
			assertThat(row(after, 1).get("errorCode")).isEqualTo("PAYLOAD_EXPIRED");
			assertState(after, 2, "FAILED", "FAILED", 0);
			assertThat(row(after, 2).get("errorCode")).isEqualTo("PAYLOAD_UNREADABLE");
			for (long id = 3; id <= 7; id++) {
				assertState(after, id, "SENT", "SENT", 1);
				assertFakeCalls(fixture, id, 1);
			}
			assertState(after, 8, "FAILED", "RATE_LIMITED", 0);
			assertThat(row(after, 8).get("errorCode")).isEqualTo("RATE_LIMITED");
			assertThat(after.get("reservedUnits")).isEqualTo(5);
			assertThat(fixture.journal()).hasSize(5);
			for (long id = 1; id <= 8; id++) { assertRemovedPayloadAndLease(after, id); }
			fixture.passed();
		}
	}

	private Fixture fixture(String name) throws IOException { return new Fixture(temporary.resolve(name), name); }

	@SuppressWarnings("unchecked")
	private static Map<String, Object> row(Map<String, Object> snapshot, long id) {
		return ((List<Map<String, Object>>) snapshot.get("rows")).stream()
			.filter(row -> ((Number) row.get("id")).longValue() == id).findFirst().orElseThrow();
	}
	private static void assertState(Map<String, Object> snapshot, long id, String outbox, String history, int attempts) {
		var row = row(snapshot, id);
		assertThat(row.get("outboxStatus")).isEqualTo(outbox);
		assertThat(row.get("deliveryStatus")).isEqualTo(history);
		assertThat(row.get("outboxAttempts")).isEqualTo(attempts);
		assertThat(row.get("deliveryAttempts")).isEqualTo(attempts);
	}
	private static void assertRemovedPayloadAndLease(Map<String, Object> snapshot, long id) {
		assertThat(row(snapshot, id).get("payloadPresent")).isEqualTo(false);
		assertThat(row(snapshot, id).get("leasePresent")).isEqualTo(false);
	}
	private static void assertFakeCalls(Fixture fixture, long id, int count) throws IOException {
		assertThat(fixture.journal().stream().filter(line -> line.startsWith(id + ","))).hasSize(count);
	}

	private final class Fixture implements AutoCloseable {
		private final Path root;
		private final String name;
		private final List<Child> children = new ArrayList<>();
		private final List<Map<String, Object>> snapshots = new ArrayList<>();
		private final List<Map<String, Object>> replacements = new ArrayList<>();
		private boolean passed;

		Fixture(Path path, String name) throws IOException {
			Files.createDirectories(path);
			this.root = path.toRealPath();
			this.name = name;
			Files.writeString(root.resolve("owned-fixture.marker"), "local-mail-process-fixture-v1");
		}

		Child start(String phase) throws Exception {
			Path arguments = root.resolve(phase + ".java.args");
			Path childTemporary = Files.createDirectories(root.resolve("child-tmp"));
			List<String> options = List.of("-Xms32m", "-Xmx384m", "-XX:MaxMetaspaceSize=192m",
				"-Djava.io.tmpdir=" + childTemporary, "-cp", classPath(),
				EmailOutboxProcessRecoveryProbe.class.getName(), phase, root.toString());
			Files.writeString(arguments, String.join("\n", options.stream().map(EmailOutboxProcessRecoveryTest::quoteArgument).toList()));
			Path log = root.resolve(phase + ".child.log");
			var builder = new ProcessBuilder(javaExecutable(), "@" + arguments);
			builder.directory(root.toFile());
			builder.redirectErrorStream(true);
			builder.redirectOutput(log.toFile());
			// No inherited app/provider/credential/agent environment reaches the narrow probe.
			builder.environment().clear();
			String systemRoot = System.getenv("SystemRoot");
			if (systemRoot != null) { builder.environment().put("SystemRoot", systemRoot); }
			var child = new Child(phase, builder.start(), log);
			children.add(child);
			return child;
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> awaitSnapshot(Child child, String kind) throws Exception {
			Path result = root.resolve(child.phase + "." + kind + ".json");
			long deadline = System.nanoTime() + Duration.ofSeconds(55).toNanos();
			while (!Files.isRegularFile(result)) {
				assertThat(child.process.isAlive()).withFailMessage("Child %s exited before barrier: %s",
					child.phase, logTail(child.log)).isTrue();
				if (System.nanoTime() >= deadline) {
					throw new AssertionError("Owned child did not reach its bounded phase barrier: " + child.phase
						+ "\n" + logTail(child.log));
				}
				Thread.sleep(25);
			}
			Map<String, Object> snapshot = JSON.readValue(Files.readString(result), Map.class);
			assertThat(((Number) snapshot.get("pid")).longValue()).isEqualTo(child.process.pid());
			assertThat(snapshot.get("senderClass")).isEqualTo("io.edupilot.mail.EmailOutboxProcessRecoveryProbe$JournalSender");
			assertThat(snapshot.get("externalProviderConstructed")).isEqualTo(false);
			snapshots.add(snapshot);
			return snapshot;
		}

		Map<String, Object> finished(Child child) throws Exception {
			var snapshot = awaitSnapshot(child, "done");
			assertThat(child.process.waitFor(15, TimeUnit.SECONDS)).as("owned child exits after its phase").isTrue();
			assertThat(child.process.exitValue()).withFailMessage("Child %s failed: %s", child.phase, logTail(child.log)).isZero();
			return snapshot;
		}

		void kill(Child child) throws Exception {
			assertThat(child.process.isAlive()).as("interrupt a physically running child JVM").isTrue();
			child.forced = true;
			child.process.destroyForcibly();
			assertThat(child.process.waitFor(15, TimeUnit.SECONDS)).as("forced child stop completes").isTrue();
			assertThat(ProcessHandle.of(child.process.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
		}

		void assertReplacement(Child previous, Child replacement) {
			assertThat(previous.process.isAlive()).isFalse();
			assertThat(replacement.process.pid()).isNotEqualTo(previous.process.pid());
			replacements.add(Map.of("oldPid", previous.process.pid(), "newPid", replacement.process.pid(),
				"oldPidConfirmedDead", true, "oldTermination", previous.forced ? "FORCED" : "NORMAL"));
		}

		List<String> journal() throws IOException {
			Path journal = root.resolve("fake-provider-journal.csv");
			return Files.exists(journal) ? Files.readAllLines(journal, StandardCharsets.UTF_8) : List.of();
		}
		void passed() { passed = true; }

		private void deleteOwnedFixtureWithBoundedRetry() throws Exception {
			Path marker = root.resolve("owned-fixture.marker");
			long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			IOException originalFailure = null;
			boolean markerRemovedByThisCleaner = false;
			while (Files.exists(root)) {
				if (!root.toRealPath().equals(root) || !root.startsWith(temporary.toRealPath())
					|| (Files.exists(marker) && !Files.readString(marker).equals("local-mail-process-fixture-v1"))
					|| (!Files.exists(marker) && !markerRemovedByThisCleaner)) {
					throw new IllegalStateException("Retry cleanup path is not the owned fixture");
				}
				try {
					try (var paths = Files.walk(root)) {
						for (var path : paths.filter(path -> !path.equals(root) && !path.equals(marker))
							.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
					}
					Files.deleteIfExists(marker);
					markerRemovedByThisCleaner = true;
					Files.deleteIfExists(root);
				} catch (IOException failure) {
					if (originalFailure == null) { originalFailure = failure; }
					else { originalFailure.addSuppressed(failure); }
					if (System.nanoTime() >= deadline) { throw originalFailure; }
					Thread.sleep(100);
				}
			}
		}

		@Override public void close() throws Exception {
			List<String> journal = journal();
			List<Map<String, Object>> processes = new ArrayList<>();
			Path reports = Path.of("build/reports/mail-process-recovery").toAbsolutePath();
			Files.createDirectories(reports);
			for (var child : children) {
				if (child.process.isAlive()) {
					child.process.destroyForcibly();
					if (!child.process.waitFor(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Owned child cleanup failed"); }
				}
				if (Files.exists(child.log)) {
					Files.copy(child.log, reports.resolve(name + "-" + child.phase + ".child.log"),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				processes.add(Map.of("phase", child.phase, "pid", child.process.pid(), "aliveAfterCleanup", child.process.isAlive(),
					"forcedByTest", child.forced, "exitCode", child.process.exitValue()));
			}
			if (!root.startsWith(temporary.toRealPath())
				|| !Files.readString(root.resolve("owned-fixture.marker")).equals("local-mail-process-fixture-v1")) {
				throw new IllegalStateException("Cleanup path is not the owned fixture");
			}
			// Preserve the marker until child files are removed; Windows may briefly retain an OS file lock.
			deleteOwnedFixtureWithBoundedRetry();
			assertThat(Files.exists(root)).as("owned fixture is actually removed").isFalse();
			Map<String, Object> evidence = new LinkedHashMap<>();
			evidence.put("schema", "local-mail-jvm-acceptance-v1");
			evidence.put("scenario", name);
			evidence.put("status", passed ? "PASS" : "FAIL");
			evidence.put("realProcessBoundary", true);
			evidence.put("database", "private persistent file-H2, MySQL mode, production JPA repositories");
			evidence.put("clock", "injected fixture clock advanced between real processes; no wall-time lease wait");
			evidence.put("provider", "fake journal only, narrow manually registered context, inherited environment cleared");
			evidence.put("realProviderBeanCount", 0);
			evidence.put("realSdkSendCalls", 0);
			evidence.put("processes", processes);
			evidence.put("replacementWitnesses", replacements);
			evidence.put("snapshots", snapshots);
			evidence.put("fakeProviderJournal", journal);
			evidence.put("fixtureDirectoryRemoved", !Files.exists(root));
			evidence.put("operatingDatabaseOrMailTouched", false);
			Files.writeString(reports.resolve(name + ".json"), JSON.writeValueAsString(evidence));
		}
	}

	private static String javaExecutable() {
		String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
		return Path.of(System.getProperty("java.home"), "bin", executable).toString();
	}
	private static String classPath() throws Exception {
		var paths = new LinkedHashSet<String>();
		for (String path : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
			if (!path.isBlank()) { paths.add(Path.of(path).toAbsolutePath().toString()); }
		}
		for (ClassLoader loader = EmailOutboxProcessRecoveryTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
			if (loader instanceof URLClassLoader urls) {
				for (var url : urls.getURLs()) {
					if (url.getProtocol().equals("file")) { paths.add(Path.of(url.toURI()).toAbsolutePath().toString()); }
				}
			}
		}
		assertThat(paths.stream().anyMatch(path -> path.endsWith("test"))).as("child runtime contains test classes").isTrue();
		return String.join(File.pathSeparator, paths);
	}
	private static String quoteArgument(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
	private static String logTail(Path path) throws IOException {
		if (!Files.exists(path)) { return "No child output"; }
		String output = Files.readString(path);
		return output.substring(Math.max(0, output.length() - 8000));
	}
	private static final class Child {
		final String phase;
		final Process process;
		final Path log;
		boolean forced;
		Child(String phase, Process process, Path log) { this.phase = phase; this.process = process; this.log = log; }
	}
}
