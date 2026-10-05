package io.edupilot.mail;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import software.amazon.awssdk.services.sesv2.SesV2Client;

/** Standalone TEST-only command. It never starts Spring, the outbox, or an HTTP server. */
public final class IsolatedSesTrial {

	private static final int MAX_MANIFEST_BYTES = 16_384;
	private static final Set<String> KEYS = Set.of("schema", "trialId", "environment", "sourceSha",
		"artifactSha256", "containerId", "imageId", "composeProject", "awsAccountId",
		"awsIdentityEvidenceRef", "from", "recipient", "recipientAlias", "region", "maxMessages",
		"stateDirectory", "authorizationRef", "observedProvider", "observedEnabled");

	private IsolatedSesTrial() {}

	public static void main(String[] args) {
		int result;
		try {
			byte[] manifest = System.in.readNBytes(MAX_MANIFEST_BYTES + 1);
			// The reviewed wrapper sets a single, pinned bootJar as the Java class path.
			String artifactHash = hashFile(Path.of(System.getProperty("java.class.path")));
			result = run(manifest, args, System.getenv(), artifactHash,
				properties -> new MailConfig().sesV2Client(properties),
				Path.of(System.getProperty("java.io.tmpdir")), System.out);
		} catch (Exception invalidInput) {
			// Exceptions may contain paths, addresses, credentials, or provider response bodies.
			System.out.println("SES_TRIAL BLOCKED_INPUT");
			result = 2;
		}
		if (result != 0) System.exit(result);
	}

	static int run(byte[] input, String[] args, Map<String, String> environment, String artifactHash,
		ClientFactory factory, Path claimRoot, PrintStream output) {
		Manifest manifest;
		boolean execute;
		try {
			manifest = Manifest.parse(input);
			execute = args.length == 3 && args[0].equals("--execute")
				&& args[1].equals("--manifest-sha256");
			if (!execute && !(args.length == 0 || (args.length == 1 && args[0].equals("--plan")))) {
				throw new IllegalArgumentException();
			}
			manifest.validate(environment, artifactHash, execute);
			if (execute && (!hash(input).equals(args[2])
				|| !hash(input).equals(environment.get("EDUPILOT_SES_TRIAL_HOST_CLAIM")))) {
				throw new IllegalArgumentException();
			}
		} catch (Exception invalidInput) {
			output.println("SES_TRIAL BLOCKED_INPUT");
			return 2;
		}
		if (!execute) {
			output.println("SES_TRIAL PLAN_NO_SEND alias=APPROVED_INBOX_1 maxMessages=1");
			return 0;
		}

		try {
			// A second process in this container also fails closed, before credential resolution.
			// The wrapper's persistent HOST claim additionally survives container replacement.
			Files.createFile(claimRoot.resolve("uteum-ses-trial-" + manifest.get("trialId") + ".claim"));
		} catch (Exception unavailableClaim) {
			output.println("SES_TRIAL BLOCKED_CLAIM");
			return 2;
		}

		MailProperties properties = new MailProperties(true, "ses", manifest.get("from"), "",
			"https://dev.uteum.com", manifest.get("region"));
		try (SesV2Client client = factory.create(properties)) {
			// Exactly one invocation; MailConfig also disables SDK retries (maxAttempts=1).
			EmailDeliveryResult receipt = new SesEmailSender(client, properties).send(new EmailMessage(
				manifest.get("recipient"), "[UTEUM] SES component trial " + manifest.get("trialId"),
				"This is one synthetic TEST message. It contains no signup or reset token. "
					+ "SES acceptance alone does not confirm inbox delivery or email verification.",
				null, EmailDeliveryType.TEST));
			if (receipt == null || receipt.providerMessageId() == null || receipt.providerMessageId().isBlank()) {
				output.println("SES_TRIAL UNKNOWN_NO_RETRY");
				return 4;
			}
		} catch (EmailSendRejection rejection) {
			// Even a definite throttle consumes this trial's budget; there is no retry.
			output.println("SES_TRIAL REJECTED_NO_RETRY");
			return 3;
		} catch (Exception unknownOutcome) {
			output.println("SES_TRIAL UNKNOWN_NO_RETRY");
			return 4;
		}
		output.println("SES_TRIAL ACCEPTED_BY_SES alias=APPROVED_INBOX_1 maxMessages=1");
		return 0;
	}

	static String hash(byte[] value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
	}

	private static String hashFile(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
			input.transferTo(java.io.OutputStream.nullOutputStream());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	@FunctionalInterface
	interface ClientFactory {
		SesV2Client create(MailProperties properties);
	}

	static final class Manifest {
		private final Map<String, String> values;

		private Manifest(Map<String, String> values) { this.values = Map.copyOf(values); }
		String get(String key) { return values.get(key); }

		static Manifest parse(byte[] input) {
			if (input.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException();
			Map<String, String> values = new HashMap<>();
			for (String line : new String(input, StandardCharsets.UTF_8).split("\n", -1)) {
				if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
				if (line.isEmpty() || line.startsWith("#")) continue;
				int separator = line.indexOf('=');
				if (separator < 1 || !line.matches("[ -~]+")) throw new IllegalArgumentException();
				String key = line.substring(0, separator);
				if (!KEYS.contains(key) || values.putIfAbsent(key, line.substring(separator + 1)) != null) {
					throw new IllegalArgumentException();
				}
			}
			if (!values.keySet().equals(KEYS)) throw new IllegalArgumentException();
			return new Manifest(values);
		}

		void validate(Map<String, String> environment, String artifactHash, boolean execute) {
			require("schema", "1");
			require("environment", "dev");
			require("from", "no-reply@uteum.com");
			require("region", "ap-northeast-2");
			require("recipientAlias", "APPROVED_INBOX_1");
			require("maxMessages", "1");
			require("observedProvider", "logging");
			require("observedEnabled", "true");
			matches("trialId", "[a-z0-9][a-z0-9-]{0,63}");
			matches("sourceSha", "[a-f0-9]{40}");
			matches("artifactSha256", "[a-f0-9]{64}");
			require("artifactSha256", artifactHash);
			matches("containerId", "[a-f0-9]{64}");
			matches("imageId", "sha256:[a-f0-9]{64}");
			matches("composeProject", "[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");
			matches("recipient", "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,63}");
			matches("stateDirectory", "/[a-zA-Z0-9_/-]+");
			if (get("stateDirectory").contains("//") || get("stateDirectory").equals("/")) {
				throw new IllegalArgumentException();
			}
			if (execute) {
				matches("awsAccountId", "[0-9]{12}");
				matches("awsIdentityEvidenceRef", "[a-zA-Z0-9:_/.-]{1,200}");
				matches("authorizationRef", "[a-zA-Z0-9:_/.-]{1,200}");
			}
			equal(environment.get("SPRING_PROFILES_ACTIVE"), "dev");
			equal(environment.get("EDUPILOT_MAIL_PROVIDER"), "logging");
			equal(environment.get("EDUPILOT_MAIL_ENABLED"), "true");
			equal(environment.get("EDUPILOT_MAIL_FROM"), get("from"));
			// Match application.yml's explicit Seoul default if AWS_REGION is absent.
			equal(environment.getOrDefault("AWS_REGION", "ap-northeast-2"), get("region"));
			// Endpoint overrides could route a signed request outside the approved SES endpoint.
			for (Map.Entry<String, String> entry : environment.entrySet()) {
				if (entry.getKey().startsWith("AWS_ENDPOINT_URL") && !entry.getValue().isBlank()) {
					throw new IllegalArgumentException();
				}
			}
		}

		private void require(String key, String value) { equal(get(key), value); }
		private void matches(String key, String regex) {
			if (!get(key).matches(regex)) throw new IllegalArgumentException();
		}
		private static void equal(String actual, String expected) {
			if (actual == null || !actual.equals(expected)) throw new IllegalArgumentException();
		}
		@Override public String toString() { return "SesTrialManifest[redacted]"; }
	}
}
