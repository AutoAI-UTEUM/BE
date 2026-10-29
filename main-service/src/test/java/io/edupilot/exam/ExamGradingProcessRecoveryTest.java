package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual OS-process boundary, production grading services and private file-H2.
 * AI is a local stub. This is neither MySQL/DEV acceptance nor provider billing proof.
 */
@Timeout(180)
class ExamGradingProcessRecoveryTest {

	@TempDir Path temporaryDirectory;
	private final List<Process> ownedProcesses = new ArrayList<>();
	private final String owner = UUID.randomUUID().toString();
	private Path fixtureRoot;
	private String scenario;

	@Test
	void realQueueSaturationSurvivesKilledJvmAndNewJvmGradesCommittedAnswersOnce() throws Exception {
		var result = crashAndRecover("saturated");
		assertThat(result.before().getProperty("aiCalls")).isEqualTo("0");
		assertThat(result.before().getProperty("queueSize")).isEqualTo("1");
		assertThat(result.before().getProperty("activeWorkers")).isEqualTo("1");
		assertThat(result.before().getProperty("main.status")).isEqualTo("SUBMITTED");
		assertThat(result.before().getProperty("main.leasePresent")).isEqualTo("false");
		assertThat(result.after().getProperty("main.status")).isEqualTo("GRADED");
		assertThat(result.after().getProperty("main.retryCount")).isEqualTo("0");
		assertThat(result.after().getProperty("aiCalls")).isEqualTo("1");
		assertThat(result.after().getProperty("notifications")).isEqualTo("1");
		assertThat(result.after().getProperty("usageRows")).isEqualTo("1");
		assertPreserved(result, "main");
	}

	@Test
	void killedLeaseHolderIsFencedBeforeExpiryAndRecoveredAfterExpiryWithoutInventingCrashUsage() throws Exception {
		var result = crashAndRecover("claimed");
		assertThat(result.before().getProperty("aiCalls")).isEqualTo("1");
		assertThat(result.before().getProperty("main.leasePresent")).isEqualTo("true");
		assertThat(result.before().getProperty("usageRows")).isEqualTo("0");
		assertThat(result.after().getProperty("beforeExpiryAiCalls")).isEqualTo("0");
		assertThat(result.after().getProperty("beforeExpiryClaim")).isEqualTo("false");
		assertThat(result.after().getProperty("staleResultApplied")).isEqualTo("false");
		assertThat(result.after().getProperty("main.status")).isEqualTo("GRADED");
		assertThat(result.after().getProperty("main.retryCount")).isEqualTo("0");
		assertThat(result.after().getProperty("aiCalls")).isEqualTo("1");
		// The killed stub call never returned usage. Only the replacement call is recorded.
		assertThat(result.after().getProperty("usageRows")).isEqualTo("1");
		assertThat(result.after().getProperty("knownCostRows")).isEqualTo("0");
		assertThat(result.after().getProperty("notifications")).isEqualTo("1");
		assertPreserved(result, "main");
	}

	@Test
	void restartRetainsThirtyMinuteCutoffAndFailsExhaustedThirdRetryWithoutCallingAiForIt() throws Exception {
		var result = crashAndRecover("cutoff");
		assertThat(result.before().getProperty("aiCalls")).isEqualTo("0");
		assertThat(result.after().getProperty("main.status")).isEqualTo("GRADING_FAILED");
		assertThat(result.after().getProperty("main.retryCount")).isEqualTo("3");
		assertThat(result.after().getProperty("recent.status")).isEqualTo("GRADED");
		assertThat(result.after().getProperty("recent.retryCount")).isEqualTo("0");
		assertThat(result.after().getProperty("expired.status")).isEqualTo("GRADED");
		assertThat(result.after().getProperty("expired.retryCount")).isEqualTo("1");
		assertThat(result.after().getProperty("aiCalls")).isEqualTo("2");
		assertThat(result.after().getProperty("notifications")).isEqualTo("2");
		assertThat(result.after().getProperty("usageRows")).isEqualTo("2");
		assertPreserved(result, "main");
		assertPreserved(result, "recent");
		assertPreserved(result, "expired");
	}

	private RestartResult crashAndRecover(String selected) throws Exception {
		scenario = selected;
		fixtureRoot = Files.createDirectory(temporaryDirectory.resolve("grading-" + selected));
		Files.writeString(fixtureRoot.resolve(".owned-grading-probe"), owner, StandardCharsets.UTF_8);
		Files.createDirectories(fixtureRoot.resolve("jvm-tmp"));
		Process original = launch("seed");
		awaitFile(original, fixtureRoot.resolve("seed.properties"), Duration.ofSeconds(70));
		Properties before = read(fixtureRoot.resolve("seed.properties"));
		assertThat(Long.parseLong(before.getProperty("pid"))).isEqualTo(original.pid());
		assertThat(original.isAlive()).as("process still blocked at the explicit crash barrier").isTrue();
		assertThat(before.getProperty("scheduledJobsDisabled")).isEqualTo("true");
		assertThat(before.getProperty("sesSdkClients")).isEqualTo("0");
		assertThat(before.getProperty("stsSdkClients")).isEqualTo("0");
		assertThat(before.getProperty("aiClients")).isEqualTo("1");
		assertThat(Integer.parseInt(before.getProperty("excludedTestClasspathClasses"))).isPositive();
		original.destroyForcibly();
		assertThat(original.waitFor(10, TimeUnit.SECONDS)).as("owned JVM termination").isTrue();
		assertThat(original.isAlive()).isFalse();
		assertThat(original.exitValue()).as("seed was killed, not gracefully stopped").isNotZero();
		PortClosure firstPort = awaitPortClosed(before);
		assertThat(Files.isRegularFile(fixtureRoot.resolve("grading.mv.db"))).isTrue();

		Process replacement = launch("recover");
		assertThat(replacement.pid()).isNotEqualTo(original.pid());
		assertThat(replacement.waitFor(70, TimeUnit.SECONDS))
			.as("replacement process completes within the bounded deadline; inspect child logs").isTrue();
		assertThat(replacement.exitValue())
			.as("replacement exit code; inspect %s", fixtureRoot.resolve("recover.log")).isZero();
		Properties after = read(fixtureRoot.resolve("recover.properties"));
		assertThat(Long.parseLong(after.getProperty("pid"))).isEqualTo(replacement.pid());
		assertThat(after.getProperty("result")).isEqualTo("PASS");
		assertThat(after.getProperty("sesSdkClients")).isEqualTo("0");
		assertThat(after.getProperty("aiClients")).isEqualTo("1");
		PortClosure replacementPort = awaitPortClosed(after);
		assertThat(after.getProperty("repeatRecoveryAiCalls")).isEqualTo(after.getProperty("aiCalls"));
		assertThat(after.getProperty("main.leasePresent")).isEqualTo("false");
		assertThat(after.getProperty("main.leaseEpoch")).isEqualTo("true");
		System.out.printf("PROCESS_RECOVERY scenario=%s firstPid=%d replacementPid=%d "
			+ "originalKilled=true replacementExit=0 result=PASS%n",
			scenario, original.pid(), replacement.pid());
		Properties receipt = new Properties();
		receipt.setProperty("scenario", scenario);
		receipt.setProperty("firstPid", Long.toString(original.pid()));
		receipt.setProperty("replacementPid", Long.toString(replacement.pid()));
		receipt.setProperty("firstPidDead", Boolean.toString(!original.isAlive()));
		receipt.setProperty("replacementPidDead", Boolean.toString(!replacement.isAlive()));
		receipt.setProperty("actualProcessBoundary", "true");
		receipt.setProperty("bothListenerPortsConnectionRefused", "true");
		receipt.setProperty("firstPortProofAttempts", Integer.toString(firstPort.attempts()));
		receipt.setProperty("firstPortProofMillis", Long.toString(firstPort.millis()));
		receipt.setProperty("replacementPortProofAttempts", Integer.toString(replacementPort.attempts()));
		receipt.setProperty("replacementPortProofMillis", Long.toString(replacementPort.millis()));
		receipt.setProperty("operatingResourcesUsed", "false");
		write(fixtureRoot.resolve("process-receipt.properties"), receipt);
		return new RestartResult(before, after);
	}

	private PortClosure awaitPortClosed(Properties state) throws Exception {
		int port = Integer.parseInt(state.getProperty("serverPort"));
		long began = System.nanoTime();
		long deadline = began + Duration.ofSeconds(5).toNanos();
		int attempts = 0;
		while (System.nanoTime() < deadline) {
			attempts++;
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
			} catch (ConnectException refused) {
				return new PortClosure(attempts, Duration.ofNanos(System.nanoTime() - began).toMillis());
			} catch (SocketTimeoutException ambiguous) {
				// A timeout is ambiguous and cannot establish that the listener is gone.
			}
			Thread.sleep(25);
		}
		throw new AssertionError("Owned listener not demonstrably closed: port=" + port + ", attempts=" + attempts);
	}

	private void assertPreserved(RestartResult result, String prefix) {
		for (String field : List.of("submissionId", "examId", "userId", "answerId", "requestHash", "answerHash")) {
			assertThat(result.after().getProperty(prefix + "." + field))
				.as("persisted %s.%s survives opening the DB in a different JVM", prefix, field)
				.isEqualTo(result.before().getProperty(prefix + "." + field));
		}
	}

	private Process launch(String mode) throws Exception {
		Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
			System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
		Path arguments = fixtureRoot.resolve(mode + "-java.args");
		List<String> args = List.of("-Xmx384m", "-XX:MaxMetaspaceSize=192m",
			"-Djava.io.tmpdir=" + fixtureRoot.resolve("jvm-tmp"),
			"-Duser.home=" + fixtureRoot.resolve("home"),
			"-cp", childClasspath(), ExamGradingProcessRecoveryProbe.class.getName(),
			fixtureRoot.toString(), owner, mode, scenario);
		Files.writeString(arguments, String.join("\n", args.stream().map(this::argfileQuote).toList()),
			StandardCharsets.UTF_8);
		ProcessBuilder builder = new ProcessBuilder(javaExecutable.toString(), "@" + arguments);
		String systemRoot = builder.environment().get("SystemRoot");
		builder.environment().clear();
		if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
		builder.environment().put("TEMP", fixtureRoot.resolve("jvm-tmp").toString());
		builder.environment().put("TMP", fixtureRoot.resolve("jvm-tmp").toString());
		builder.directory(fixtureRoot.toFile());
		builder.redirectErrorStream(true).redirectOutput(fixtureRoot.resolve(mode + ".log").toFile());
		Process process = builder.start();
		ownedProcesses.add(process);
		return process;
	}

	private String childClasspath() {
		LinkedHashSet<String> entries = new LinkedHashSet<>();
		for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
			if (loader instanceof URLClassLoader urls) {
				for (var url : urls.getURLs()) {
					try { entries.add(Path.of(url.toURI()).toAbsolutePath().toString()); }
					catch (Exception exception) { throw new IllegalStateException("Non-file child classpath", exception); }
				}
			}
		}
		for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
			entries.add(Path.of(entry).toAbsolutePath().toString());
		}
		return String.join(File.pathSeparator, entries);
	}

	private String argfileQuote(String argument) {
		return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private void awaitFile(Process child, Path file, Duration timeout) throws Exception {
		long end = System.nanoTime() + timeout.toNanos();
		while (!Files.isRegularFile(file) && child.isAlive() && System.nanoTime() < end) {
			Thread.sleep(25);
		}
		assertThat(Files.isRegularFile(file))
			.as("phase barrier file; childPid=%d, alive=%s, log=%s",
				child.pid(), child.isAlive(), fixtureRoot.resolve("seed.log")).isTrue();
	}

	@AfterEach
	void stopOnlyOwnedProcessesArchiveAndRemovePrivateDatabase() throws Exception {
		for (Process process : ownedProcesses) {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
			}
			assertThat(process.isAlive()).as("owned PID %s released", process.pid()).isFalse();
		}
		if (fixtureRoot == null) return;
		Path expected = temporaryDirectory.toAbsolutePath().normalize();
		assertThat(fixtureRoot.toAbsolutePath().normalize().startsWith(expected)).isTrue();
		assertThat(Files.readString(fixtureRoot.resolve(".owned-grading-probe"))).isEqualTo(owner);
		Path archive = Path.of("build", "process-recovery-evidence", scenario + "-" + owner).toAbsolutePath();
		Files.createDirectories(archive);
		try (var paths = Files.list(fixtureRoot)) {
			for (Path source : paths.filter(path -> path.toString().endsWith(".properties")
					|| path.toString().endsWith(".log")).toList()) {
				Files.copy(source, archive.resolve(source.getFileName()));
			}
		}
		long deletionDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		IOException lastDeletionFailure = null;
		while (Files.exists(fixtureRoot) && System.nanoTime() < deletionDeadline) {
			try {
				List<Path> targets;
				try (var paths = Files.walk(fixtureRoot)) { targets = paths.sorted(Comparator.reverseOrder()).toList(); }
				for (Path target : targets) Files.deleteIfExists(target);
			} catch (IOException transientOwnedFileLock) {
				lastDeletionFailure = transientOwnedFileLock;
				Thread.sleep(25);
			}
		}
		if (Files.exists(fixtureRoot)) throw new IOException("Owned private root could not be removed", lastDeletionFailure);
		assertThat(Files.exists(fixtureRoot)).as("private file DB and child files removed").isFalse();
		Properties cleanup = new Properties();
		cleanup.setProperty("allOwnedPidsDead", "true");
		cleanup.setProperty("privateDatabaseRemoved", "true");
		cleanup.setProperty("privateRootRemoved", "true");
		cleanup.setProperty("scope", "only marked JUnit TempDir fixture root");
		write(archive.resolve("cleanup.properties"), cleanup);
		System.out.printf("PROCESS_CLEANUP scenario=%s allOwnedPidsDead=true privateDatabaseRemoved=true%n", scenario);
	}

	private Properties read(Path path) throws IOException {
		Properties result = new Properties();
		try (var in = Files.newInputStream(path)) { result.load(in); }
		return result;
	}

	private void write(Path path, Properties values) throws IOException {
		try (var out = Files.newOutputStream(path)) { values.store(out, "synthetic process acceptance"); }
	}

	private record PortClosure(int attempts, long millis) { }
	private record RestartResult(Properties before, Properties after) { }
}
