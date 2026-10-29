package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.edupilot.auth.JwtProperties;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

class GuardianWebServiceTest {
	private final GuardianPhoneProvider provider = mock(GuardianPhoneProvider.class);
	private final GuardianWebPersistence persistence = mock(GuardianWebPersistence.class);
	private final GuardianWebSecrets secrets = new GuardianWebSecrets(new JwtProperties(
		"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", Duration.ofHours(1)));

	@Test
	void defaultDisabledFlowNeverTouchesPersistenceOrProvider() {
		var service = service(false, "https://guardian.example.invalid", "a".repeat(64));
		assertUnavailable(() -> service.issue(1L, "192.0.2.1"));
		assertUnavailable(() -> service.view(new GuardianWebDtos.Token(secrets.token()), "192.0.2.2"));
		assertUnavailable(() -> service.consent(new GuardianWebDtos.Consent(secrets.token(), "v1", true, true,
			"+12025550123"), "192.0.2.3"));
		assertUnavailable(() -> service.verify(new GuardianWebDtos.Code(secrets.token(), "123456"), "192.0.2.4"));
		assertUnavailable(() -> service.dispute(new GuardianWebDtos.Token(secrets.token()), "192.0.2.5"));
		verifyNoInteractions(provider, persistence);
	}

	@Test
	void enabledButDisconnectedProviderCannotIssueOrPretendSuccess() {
		var service = service(true, "https://guardian.example.invalid", "a".repeat(64));
		assertUnavailable(() -> service.issue(1L, "192.0.2.1"));
		verify(provider).connected();
		verifyNoInteractions(persistence);
		var disconnected = new GuardianWebConfig().disconnectedGuardianPhoneProvider();
		assertThat(disconnected.connected()).isFalse();
		assertThatThrownBy(() -> disconnected.send("+12025550123", "synthetic", java.time.Instant.now()))
			.isInstanceOf(GuardianPhoneProvider.Failure.class);
		assertThatThrownBy(() -> disconnected.verify("synthetic", "123456", "synthetic", java.time.Instant.now()))
			.isInstanceOf(GuardianPhoneProvider.Failure.class);
	}

	@Test
	void incompleteOrUnsafeConfigurationFailsBeforeAnyIo() {
		assertUnavailable(() -> service(true, "http://guardian.example.invalid", "a".repeat(64)).issue(1L, "192.0.2.1"));
		assertUnavailable(() -> service(true, "https://guardian.example.invalid/?token=unsafe", "a".repeat(64)).issue(1L, "192.0.2.1"));
		assertUnavailable(() -> service(true, "https://user:pass@guardian.example.invalid", "a".repeat(64)).issue(1L, "192.0.2.1"));
		assertUnavailable(() -> service(true, "https://guardian.example.invalid", "missing").issue(1L, "192.0.2.1"));
		verifyNoInteractions(provider, persistence);
	}

	@Test
	void tokensAreUnpredictableHashableAndPhoneFingerprintsAreKeyed() {
		String first = secrets.token();
		assertThat(first).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(secrets.token());
		assertThat(GuardianWebSecrets.hash(first)).matches("[a-f0-9]{64}").doesNotContain(first);
		assertThat(secrets.phoneFingerprint("+12025550123")).matches("[a-f0-9]{64}")
			.isEqualTo(secrets.phoneFingerprint("+12025550123"))
			.isNotEqualTo(secrets.phoneFingerprint("+12025550124"))
			.isNotEqualTo(GuardianWebSecrets.hash("+12025550123"));
	}

	@Test
	void requestAndProviderDtoStringsNeverExposeBearerPhoneOrCode() {
		String token = secrets.token();
		assertThat(new GuardianWebDtos.Token(token).toString()).doesNotContain(token);
		assertThat(new GuardianWebDtos.Link("https://guardian.example.invalid/#token=" + token, java.time.Instant.now()).toString())
			.doesNotContain(token);
		assertThat(new GuardianWebDtos.Consent(token, "v1", true, true, "+12025550123").toString())
			.doesNotContain(token, "+12025550123");
		assertThat(new GuardianWebDtos.Code(token, "123456").toString()).doesNotContain(token, "123456");
		assertThat(new GuardianPhoneProvider.Receipt("synthetic-private-reference").toString()).doesNotContain("synthetic-private-reference");
	}

	private GuardianWebService service(boolean enabled, String portal, String digest) {
		return new GuardianWebService(new GuardianWebProperties(enabled, Duration.ofMinutes(30), Duration.ofMinutes(5), 3,
			portal, "v1", digest, "https://guardian.example.invalid/notice"), provider, persistence, secrets);
	}
	private void assertUnavailable(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.GUARDIAN_VERIFICATION_UNAVAILABLE));
	}
}
