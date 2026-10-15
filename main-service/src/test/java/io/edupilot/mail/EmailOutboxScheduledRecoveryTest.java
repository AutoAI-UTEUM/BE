package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class EmailOutboxScheduledRecoveryTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	@TempDir Path temporary;

	@Test
	void bootStartedProductionSchedulerRecoversExistingReadyAndClaimedRowsWithoutManualDispatch() throws Exception {
		try (var fixture = new Fixture("normal")) {
			var producer = fixture.seedAndKill();
			var consumer = fixture.start("normal");
			var result = fixture.finished(consumer);
			assertThat(result.get("states")).isEqualTo(Map.of("1", "SENT", "2", "SENT", "3", "FAILED", "4", "UNKNOWN"));
			assertScheduledOnly(result);
			assertThat(fixture.journal()).containsExactlyInAnyOrder("1," + consumer.pid(), "2," + consumer.pid());
			assertThat(producer.pid()).isNotEqualTo(consumer.pid());
			fixture.passed = true;
		}
	}

	@Test
	void pausedBootStillAutomaticallyRecoversLeasesAndExpiresPayloadsWithoutSending() throws Exception {
		try (var fixture = new Fixture("paused")) {
			fixture.seedAndKill();
			var consumer = fixture.start("paused");
			var result = fixture.finished(consumer);
			assertThat(result.get("states")).isEqualTo(Map.of("1", "READY", "2", "READY", "3", "FAILED", "4", "UNKNOWN"));
			assertScheduledOnly(result);
			assertThat(fixture.journal()).isEmpty();
			fixture.passed = true;
		}
	}

	@Test
	void missingProductionSchedulingRegistrationLeavesPersistedWorkUntouched() throws Exception {
		try (var fixture = new Fixture("without-scheduling")) {
			fixture.seedAndKill();
			var consumer = fixture.start("without-scheduling");
			var result = fixture.finished(consumer);
			assertThat(result.get("registeredRecoveryTasks")).isEqualTo(0);
			assertThat(result.get("states")).isEqualTo(Map.of("1", "READY", "2", "CLAIMED", "3", "READY", "4", "SENDING"));
			assertThat(fixture.journal()).isEmpty();
			fixture.passed = true;
		}
	}

	private static void assertScheduledOnly(Map<String, Object> result) {
		assertThat(result.get("registeredRecoveryTasks")).isEqualTo(1);
		assertThat(result.get("explicitRecoveryOrKickCalls")).isEqualTo(0);
		assertThat(result.get("realSdkClients")).isEqualTo(0);
		assertThat(result.get("webServerStarted")).isEqualTo(false);
	}

	private final class Fixture implements AutoCloseable {
		final String name;
		final Path root;
		final List<Process> children = new ArrayList<>();
		final List<Map<String, Object>> snapshots = new ArrayList<>();
		boolean passed;

		Fixture(String name) throws Exception {
			this.name = name;
			root = Files.createDirectory(temporary.resolve(name)).toRealPath();
			Files.writeString(root.resolve("owned-fixture.marker"), EmailOutboxScheduledRecoveryProbe.MARKER);
		}

		Process seedAndKill() throws Exception {
			var producer = start("seed");
			var before = await(producer, "seed.json");
			assertThat(before.get("states")).isEqualTo(Map.of("1", "READY", "2", "CLAIMED", "3", "READY", "4", "SENDING"));
			assertThat(journal()).isEmpty();
			producer.destroyForcibly();
			assertThat(producer.waitFor(10, TimeUnit.SECONDS)).isTrue();
			assertThat(producer.isAlive()).isFalse();
			return producer;
		}

		Process start(String phase) throws Exception {
			Path argumentFile = root.resolve(phase + ".args");
			Path childTemporary = Files.createDirectories(root.resolve("child-tmp"));
			List<String> arguments = List.of("-Xms32m", "-Xmx384m", "-XX:MaxMetaspaceSize=192m",
				"-Djava.io.tmpdir=" + childTemporary, "-cp", classPath(),
				EmailOutboxScheduledRecoveryProbe.class.getName(), phase, root.toString());
			Files.writeString(argumentFile, String.join("\n", arguments.stream().map(EmailOutboxScheduledRecoveryTest::quote).toList()));
			String java = Path.of(System.getProperty("java.home"), "bin",
				System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
			var builder = new ProcessBuilder(java, "@" + argumentFile).directory(root.toFile());
			builder.redirectErrorStream(true).redirectOutput(root.resolve(phase + ".log").toFile());
			builder.environment().clear();
			String systemRoot = System.getenv("SystemRoot");
			if (systemRoot != null) { builder.environment().put("SystemRoot", systemRoot); }
			var process = builder.start();
			children.add(process);
			return process;
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> await(Process process, String filename) throws Exception {
			Path snapshot = root.resolve(filename);
			long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
			while (!Files.exists(snapshot)) {
				if (!process.isAlive() || System.nanoTime() >= deadline) {
					String phase = filename.equals("seed.json") ? "seed" : name;
					String output = Files.readString(root.resolve(phase + ".log"));
					throw new AssertionError("Owned probe did not produce " + filename + ": "
						+ output.substring(Math.max(0, output.length() - 8000)));
				}
				Thread.sleep(50);
			}
			Map<String, Object> value = JSON.readValue(Files.readString(snapshot), Map.class);
			assertThat(((Number) value.get("pid")).longValue()).isEqualTo(process.pid());
			snapshots.add(value);
			return value;
		}

		Map<String, Object> finished(Process consumer) throws Exception {
			var value = await(consumer, "done.json");
			assertThat(consumer.waitFor(10, TimeUnit.SECONDS)).isTrue();
			assertThat(consumer.exitValue()).isZero();
			return value;
		}

		List<String> journal() throws IOException {
			Path file = root.resolve("fake-provider-journal.csv");
			return Files.exists(file) ? Files.readAllLines(file) : List.of();
		}

		@Override public void close() throws Exception {
			for (var child : children) {
				if (child.isAlive()) { child.destroyForcibly(); }
				assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
				assertThat(child.isAlive()).isFalse();
			}
			Path reports = Files.createDirectories(Path.of("build/mail-scheduled-recovery"));
			var evidence = Map.of("scenario", name, "status", passed ? "PASS" : "FAIL", "snapshots", snapshots,
				"childPids", children.stream().map(Process::pid).toList(), "allChildrenStopped", true,
				"journal", journal(), "scope", "Boot mail slice; file-H2; production scheduling and executors; synthetic sender",
				"realMailCalls", 0, "liveServerActions", 0);
			Files.writeString(reports.resolve(name + ".json"), JSON.writeValueAsString(evidence));
			if (!root.toRealPath().startsWith(temporary.toRealPath())
				|| !Files.readString(root.resolve("owned-fixture.marker")).equals(EmailOutboxScheduledRecoveryProbe.MARKER)) {
				throw new IllegalStateException("Cleanup must remain inside the marked owned fixture");
			}
			long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (true) {
				try (var paths = Files.walk(root)) {
					for (var path : paths.filter(p -> !p.equals(root) && !p.getFileName().toString().equals("owned-fixture.marker"))
						.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
					break;
				} catch (IOException locked) {
					if (System.nanoTime() >= deadline) { throw locked; }
					Thread.sleep(50);
				}
			}
			Files.delete(root.resolve("owned-fixture.marker"));
			Files.delete(root);
			assertThat(Files.exists(root)).isFalse();
		}
	}

	private static String classPath() throws Exception {
		var entries = new LinkedHashSet<String>();
		for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
			if (!entry.isBlank()) { entries.add(Path.of(entry).toAbsolutePath().toString()); }
		}
		for (ClassLoader loader = EmailOutboxScheduledRecoveryTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
			if (loader instanceof URLClassLoader urls) {
				for (var url : urls.getURLs()) {
					if (url.getProtocol().equals("file")) { entries.add(Path.of(url.toURI()).toAbsolutePath().toString()); }
				}
			}
		}
		return String.join(File.pathSeparator, entries);
	}

	private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
