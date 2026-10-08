package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

class IsolatedSesTrialTest {
	private static final String ARTIFACT = "b".repeat(64);
	@TempDir Path claims;
	private final SesV2Client client = mock(SesV2Client.class);
	private final AtomicInteger factories = new AtomicInteger();
	private final StsClient identityClient = mock(StsClient.class);
	private final AtomicInteger identityFactories = new AtomicInteger();

	@Test
	void defaultAndExplicitPlanNeverCreateClientOrClaim() throws Exception {
		byte[] input = manifest(Map.of("authorizationRef", "", "awsAccountId", "", "awsIdentityEvidenceRef", ""));
		assertThat(run(input, new String[0], environment(input)).text()).contains("PLAN_NO_SEND");
		assertThat(run(input, new String[]{"--plan"}, environment(input)).code()).isZero();
		assertThat(factories).hasValue(0);
		assertThat(claims.toFile().list()).isEmpty();
	}

	@Test
	void approvedMockExecutionUsesOneFixedSyntheticTestRequestAndCannotBeRepeated() throws Exception {
		when(client.sendEmail(any(SendEmailRequest.class)))
			.thenReturn(SendEmailResponse.builder().messageId("mock-only-id").build());
		byte[] input = manifest(Map.of());
		assertThat(execute(input).text()).contains("ACCEPTED_BY_SES")
			.doesNotContain("owner@example.com", "mock-only-id");
		assertThat(execute(input).text()).contains("BLOCKED_CLAIM");
		assertThat(factories).hasValue(1);
		var request = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
		verify(client, times(1)).sendEmail(request.capture());
		assertThat(request.getValue().fromEmailAddress()).isEqualTo("no-reply@uteum.com");
		assertThat(request.getValue().destination().toAddresses()).containsExactly("owner@example.com");
		assertThat(request.getValue().destination().ccAddresses()).isEmpty();
		assertThat(request.getValue().destination().bccAddresses()).isEmpty();
		assertThat(request.getValue().replyToAddresses()).isEmpty();
		assertThat(request.getValue().content().simple().subject().data()).endsWith("ses-component-20261005");
		assertThat(request.getValue().content().simple().body().text().data()).doesNotContain("http", "token=");
		verify(client).close();
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void invalidScopeNeverCreatesClientOrClaim(String field, String value) throws Exception {
		byte[] input = manifest(Map.of(field, value));
		Result result = execute(input);
		assertThat(result.code()).isEqualTo(2);
		assertThat(result.text()).isEqualTo("SES_TRIAL BLOCKED_INPUT\n");
		assertThat(factories).hasValue(0);
		assertThat(claims.toFile().list()).isEmpty();
	}

	static Stream<Arguments> invalidFields() {
		return Stream.of(Arguments.of("schema", "2"), Arguments.of("environment", "prod"),
			Arguments.of("from", "someone@uteum.com"), Arguments.of("region", "us-east-1"),
			Arguments.of("recipientAlias", "ALL_USERS"), Arguments.of("maxMessages", "2"),
			Arguments.of("observedProvider", "ses"), Arguments.of("observedEnabled", "false"),
			Arguments.of("trialId", "../other"), Arguments.of("sourceSha", "unknown"),
			Arguments.of("artifactSha256", "c".repeat(64)), Arguments.of("containerId", "short-id"),
			Arguments.of("imageId", "latest"), Arguments.of("composeProject", "dev;cmd"),
			Arguments.of("recipient", "owner@example.com,other@example.com"),
			Arguments.of("recipient", "Owner <owner@example.com>"),
			Arguments.of("stateDirectory", "/"), Arguments.of("stateDirectory", "/tmp/../other"),
			Arguments.of("authorizationRef", ""), Arguments.of("awsAccountId", ""),
			Arguments.of("awsIdentityEvidenceRef", ""));
	}

	@ParameterizedTest
	@MethodSource("invalidEnvironment")
	void changedContainerConfigurationIsBlocked(String key, String value) throws Exception {
		byte[] input = manifest(Map.of());
		Map<String, String> env = environment(input);
		env.put(key, value);
		assertThat(run(input, executionArgs(input), env).code()).isEqualTo(2);
		assertThat(factories).hasValue(0);
	}

	static Stream<Arguments> invalidEnvironment() {
		return Stream.of(Arguments.of("SPRING_PROFILES_ACTIVE", "prod"),
			Arguments.of("SPRING_PROFILES_ACTIVE", "dev,prod"),
			Arguments.of("EDUPILOT_MAIL_PROVIDER", "ses"), Arguments.of("EDUPILOT_MAIL_ENABLED", "false"),
			Arguments.of("EDUPILOT_MAIL_FROM", "other@uteum.com"), Arguments.of("AWS_REGION", "us-east-1"),
			Arguments.of("AWS_ENDPOINT_URL", "https://other.example.com"),
			Arguments.of("AWS_ENDPOINT_URL_SES_V2", "https://other.example.com"),
			Arguments.of("EDUPILOT_SES_TRIAL_HOST_CLAIM", "missing-claim"));
	}

	@Test
	void missingHostClaimWrongHashAndUnexpectedArgumentsNeverInvokeClient() throws Exception {
		byte[] input = manifest(Map.of());
		Map<String, String> env = environment(input);
		env.remove("EDUPILOT_SES_TRIAL_HOST_CLAIM");
		assertThat(run(input, executionArgs(input), env).code()).isEqualTo(2);
		assertThat(run(input, new String[]{"--execute", "--manifest-sha256", "f".repeat(64)}, environment(input)).code())
			.isEqualTo(2);
		assertThat(run(input, new String[]{"--execute"}, environment(input)).code()).isEqualTo(2);
		assertThat(run(input, new String[]{"--send-all"}, environment(input)).code()).isEqualTo(2);
		assertThat(factories).hasValue(0);
	}

	@Test
	void duplicateUnknownMissingOversizedAndControlInputsAreRejectedWithoutDisclosure() throws Exception {
		String valid = new String(manifest(Map.of()), StandardCharsets.UTF_8);
		for (String input : new String[]{valid + "recipient=other@example.com\n", valid + "bcc=other@example.com\n",
			valid.replace("recipient=owner@example.com\n", ""), valid + "#" + "x".repeat(16_384),
			valid.replace("owner@example.com", "owner\u0000@example.com")}) {
			byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
			assertThat(run(bytes, new String[]{"--plan"}, environment(bytes)).text())
				.isEqualTo("SES_TRIAL BLOCKED_INPUT\n");
		}
		assertThat(factories).hasValue(0);
	}

	@ParameterizedTest
	@MethodSource("providerFailures")
	void anyFailureConsumesBudgetAndNeverPrintsProviderMessageOrRetries(RuntimeException failure, int code) throws Exception {
		when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(failure);
		byte[] input = manifest(Map.of());
		Result first = execute(input);
		assertThat(first.code()).isEqualTo(code);
		assertThat(first.text()).doesNotContain("owner@example.com", "secret-token", "raw-body");
		assertThat(execute(input).text()).contains("BLOCKED_CLAIM");
		verify(client, times(1)).sendEmail(any(SendEmailRequest.class));
		assertThat(factories).hasValue(1);
	}

	static Stream<Arguments> providerFailures() {
		return Stream.of(Arguments.of(SesV2Exception.builder().statusCode(429).message("raw-body").build(), 3),
			Arguments.of(SesV2Exception.builder().statusCode(403).message("owner@example.com").build(), 3),
			Arguments.of(SesV2Exception.builder().statusCode(500).message("secret-token").build(), 4),
			Arguments.of(new IllegalStateException("raw-body owner@example.com secret-token"), 4));
	}

	@Test
	void emptyProviderReceiptRemainsUnknownAndDoesNotRetry() throws Exception {
		when(client.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().build());
		byte[] input = manifest(Map.of());
		assertThat(execute(input).text()).contains("UNKNOWN_NO_RETRY");
		assertThat(execute(input).text()).contains("BLOCKED_CLAIM");
		verify(client, times(1)).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void clientConstructionFailureConsumesClaimBeforeCredentialResolutionCanBeRepeated() throws Exception {
		byte[] input = manifest(Map.of());
		var out = new ByteArrayOutputStream();
		assertThat(IsolatedSesTrial.run(input, executionArgs(input), environment(input), ARTIFACT,
			properties -> { throw new IllegalStateException("secret-token"); },
			properties -> { throw new AssertionError("Identity client must not be created"); },
			claims, new PrintStream(out))).isEqualTo(4);
		assertThat(execute(input).text()).contains("BLOCKED_CLAIM");
		assertThat(factories).hasValue(0);
		verify(client, never()).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void clientCloseFailureProducesOneConservativeOutcomeAndCannotReopenBudget() throws Exception {
		when(client.sendEmail(any(SendEmailRequest.class)))
			.thenReturn(SendEmailResponse.builder().messageId("mock-only-id").build());
		doThrow(new IllegalStateException("raw-body")).when(client).close();
		byte[] input = manifest(Map.of());
		assertThat(execute(input).text()).isEqualTo("SES_TRIAL UNKNOWN_NO_RETRY\n");
		assertThat(execute(input).text()).contains("BLOCKED_CLAIM");
		verify(client, times(1)).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void concurrentProcessesShareOneAtomicClaimAndInvokeSdkOnce() throws Exception {
		when(client.sendEmail(any(SendEmailRequest.class)))
			.thenReturn(SendEmailResponse.builder().messageId("mock-only-id").build());
		byte[] input = manifest(Map.of());
		try (var executor = Executors.newFixedThreadPool(8)) {
			var tasks = new ArrayList<Callable<Result>>();
			for (int i = 0; i < 16; i++) tasks.add(() -> execute(input));
			var results = executor.invokeAll(tasks);
			int accepted = 0;
			for (var result : results) if (result.get().code() == 0) accepted++;
			assertThat(accepted).isEqualTo(1);
		}
		assertThat(factories).hasValue(1);
		verify(client, times(1)).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void manifestToStringDoesNotExposeReceiverOrAuthorization() throws Exception {
		assertThat(IsolatedSesTrial.Manifest.parse(manifest(Map.of())).toString())
			.isEqualTo("SesTrialManifest[redacted]");
	}

	private Result execute(byte[] input) throws Exception { return run(input, executionArgs(input), environment(input)); }
	private String[] executionArgs(byte[] input) throws Exception {
		return new String[]{"--execute", "--manifest-sha256", IsolatedSesTrial.hash(input)};
	}
	private Result run(byte[] input, String[] args, Map<String, String> env) {
		var output = new ByteArrayOutputStream();
		int code = IsolatedSesTrial.run(input, args, env, ARTIFACT,
			properties -> { factories.incrementAndGet(); return client; },
			properties -> { identityFactories.incrementAndGet(); return identityClient; }, claims,
			new PrintStream(output, true, StandardCharsets.UTF_8));
		return new Result(code, output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
	}
	private Map<String, String> environment(byte[] input) throws Exception {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("SPRING_PROFILES_ACTIVE", "dev");
		env.put("EDUPILOT_MAIL_PROVIDER", "logging");
		env.put("EDUPILOT_MAIL_ENABLED", "true");
		env.put("EDUPILOT_MAIL_FROM", "no-reply@uteum.com");
		env.put("AWS_REGION", "ap-northeast-2");
		env.put("EDUPILOT_SES_TRIAL_HOST_CLAIM", IsolatedSesTrial.hash(input));
		return env;
	}
	private byte[] manifest(Map<String, String> overrides) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("schema", "1");
		fields.put("trialId", "ses-component-20261005");
		fields.put("environment", "dev");
		fields.put("sourceSha", "a".repeat(40));
		fields.put("artifactSha256", ARTIFACT);
		fields.put("containerId", "c".repeat(64));
		fields.put("imageId", "sha256:" + "d".repeat(64));
		fields.put("composeProject", "uteum-dev");
		fields.put("awsAccountId", "123456789012");
		fields.put("awsIdentityEvidenceRef", "test-only:identity");
		fields.put("from", "no-reply@uteum.com");
		fields.put("recipient", "owner@example.com");
		fields.put("recipientAlias", "APPROVED_INBOX_1");
		fields.put("region", "ap-northeast-2");
		fields.put("maxMessages", "1");
		fields.put("approvedOperation", "SEND");
		fields.put("stateDirectory", "/var/lib/uteum-mail-trial");
		fields.put("authorizationRef", "test-only:authorization");
		fields.put("observedProvider", "logging");
		fields.put("observedEnabled", "true");
		fields.putAll(overrides);
		StringBuilder content = new StringBuilder();
		fields.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));
		return content.toString().getBytes(StandardCharsets.UTF_8);
	}
	private record Result(int code, String text) {}

	@Test
	void identityReadUsesStsOnlyAndOutputsOnlyAccountAndArn() throws Exception {
		byte[] input = identityManifest();
		String arn = "arn:aws:sts::123456789012:assumed-role/FixtureRole/i-fixture";
		when(identityClient.getCallerIdentity(any(GetCallerIdentityRequest.class)))
			.thenReturn(GetCallerIdentityResponse.builder().account("123456789012").arn(arn)
				.userId("must-not-print-secret-token").build());
		Result result = readIdentity(input);
		assertThat(result.code()).isZero();
		assertThat(result.text()).isEqualTo("{\"Account\":\"123456789012\",\"Arn\":\"" + arn + "\"}\n");
		assertThat(factories).hasValue(0);
		assertThat(identityFactories).hasValue(1);
		verify(client, never()).sendEmail(any(SendEmailRequest.class));
		verify(identityClient, times(1)).getCallerIdentity(any(GetCallerIdentityRequest.class));
		assertThat(claims.resolve("uteum-ses-trial-ses-component-20261005.identity.claim")).exists();
		assertThat(claims.resolve("uteum-ses-trial-ses-component-20261005.claim")).doesNotExist();
		assertThat(readIdentity(input).text()).contains("BLOCKED_CLAIM");
	}

	@Test
	void identityApprovalCannotAuthorizeMailAndSendApprovalCannotAuthorizeIdentityRead() throws Exception {
		byte[] identityInput = identityManifest();
		assertThat(execute(identityInput).code()).isEqualTo(2);
		assertThat(readIdentity(manifest(Map.of())).code()).isEqualTo(2);
		assertThat(factories).hasValue(0);
		assertThat(identityFactories).hasValue(0);
		assertThat(claims.toFile().list()).isEmpty();
	}

	@Test
	void identityPlanWithNoApprovalNeverCreatesAnyClientOrClaim() throws Exception {
		byte[] input = manifest(Map.of("approvedOperation", "IDENTITY", "maxMessages", "0",
			"authorizationRef", "", "awsIdentityEvidenceRef", ""));
		assertThat(run(input, new String[]{"--plan"}, environment(input)).code()).isZero();
		assertThat(factories).hasValue(0);
		assertThat(identityFactories).hasValue(0);
		assertThat(claims.toFile().list()).isEmpty();
	}

	@Test
	void identityFailureDoesNotPrintExceptionOrRetryAndCannotSpendMailBudget() throws Exception {
		byte[] input = identityManifest();
		when(identityClient.getCallerIdentity(any(GetCallerIdentityRequest.class)))
			.thenThrow(new IllegalStateException("raw-body secret-token owner@example.com"));
		Result result = readIdentity(input);
		assertThat(result.code()).isEqualTo(4);
		assertThat(result.text()).isEqualTo("SES_TRIAL IDENTITY_READ_FAILED_NO_RETRY\n");
		assertThat(readIdentity(input).text()).contains("BLOCKED_CLAIM");
		verify(identityClient, times(1)).getCallerIdentity(any(GetCallerIdentityRequest.class));
		verify(client, never()).sendEmail(any(SendEmailRequest.class));
		assertThat(claims.resolve("uteum-ses-trial-ses-component-20261005.claim")).doesNotExist();
	}

	@Test
	void differentAccountIsReturnedAsMetadataWithFailureExitAndNoMail() throws Exception {
		when(identityClient.getCallerIdentity(any(GetCallerIdentityRequest.class)))
			.thenReturn(GetCallerIdentityResponse.builder().account("987654321098")
				.arn("arn:aws:sts::987654321098:assumed-role/OtherRole/i-fixture").build());
		Result result = readIdentity(identityManifest());
		assertThat(result.code()).isEqualTo(2);
		assertThat(result.text()).contains("987654321098", "OtherRole");
		verify(client, never()).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void malformedIdentityResponseCannotInjectOutput() throws Exception {
		when(identityClient.getCallerIdentity(any(GetCallerIdentityRequest.class)))
			.thenReturn(GetCallerIdentityResponse.builder().account("123456789012")
				.arn("arn:aws:sts::123456789012:assumed-role/FixtureRole\nsecret-token").build());
		assertThat(readIdentity(identityManifest()).text()).isEqualTo("SES_TRIAL IDENTITY_READ_FAILED_NO_RETRY\n");
		verify(client, never()).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void sesAndIdentityClientUseSameDefaultCredentialProviderAndSingleCallConfiguration() {
		MailConfig config = new MailConfig();
		MailProperties properties = new MailProperties(true, "ses", "no-reply@uteum.com", "",
			"https://dev.uteum.com", "ap-northeast-2");
		try (SesV2Client ses = config.sesV2Client(properties); StsClient sts = config.identityClient(properties)) {
			var sesConfig = ses.serviceClientConfiguration();
			var stsConfig = sts.serviceClientConfiguration();
			assertThat(stsConfig.credentialsProvider()).isSameAs(sesConfig.credentialsProvider());
			assertThat(stsConfig.region()).isEqualTo(sesConfig.region());
			assertThat(stsConfig.overrideConfiguration().apiCallTimeout())
				.contains(java.time.Duration.ofSeconds(10));
			assertThat(sesConfig.overrideConfiguration().apiCallTimeout())
				.isEqualTo(stsConfig.overrideConfiguration().apiCallTimeout());
			assertThat(stsConfig.overrideConfiguration().retryStrategy().orElseThrow().maxAttempts()).isEqualTo(1);
			assertThat(sesConfig.overrideConfiguration().retryStrategy().orElseThrow().maxAttempts()).isEqualTo(1);
		}
	}

	private byte[] identityManifest() {
		return manifest(Map.of("approvedOperation", "IDENTITY", "maxMessages", "0", "awsIdentityEvidenceRef", ""));
	}
	private Result readIdentity(byte[] input) throws Exception {
		return run(input, new String[]{"--identity", "--manifest-sha256", IsolatedSesTrial.hash(input)}, environment(input));
	}
}
