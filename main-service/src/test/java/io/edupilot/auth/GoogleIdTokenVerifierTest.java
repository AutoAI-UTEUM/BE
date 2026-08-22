package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class GoogleIdTokenVerifierTest {

	private MockWebServer server;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void verifiedGoogleTokenReturnsProfile() throws Exception {
		enqueueTokenInfo("client-id", "true");
		GoogleIdTokenVerifier verifier = verifier("client-id");

		GoogleProfile profile = verifier.verify("sensitive-id-token");

		assertThat(profile).isEqualTo(new GoogleProfile(
			"google-subject",
			"user@example.com",
			"구글 사용자"
		));
		assertThat(server.takeRequest().getPath())
			.isEqualTo("/tokeninfo?id_token=sensitive-id-token");
	}

	@Test
	void audienceMismatchAndUnverifiedEmailAreInvalid() {
		enqueueTokenInfo("other-client", "true");
		assertTokenInvalid(() -> verifier("client-id").verify("id-token"));

		enqueueTokenInfo("client-id", "false");
		assertTokenInvalid(() -> verifier("client-id").verify("id-token"));
	}

	@Test
	void missingClientConfigurationUsesValidationErrorWithoutHttpCall() {
		GoogleIdTokenVerifier verifier = verifier("");

		assertThatThrownBy(() -> verifier.verify("id-token"))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.VALIDATION_FAILED)
			);
		assertThat(server.getRequestCount()).isZero();
	}

	private GoogleIdTokenVerifier verifier(String clientId) {
		return new GoogleIdTokenVerifier(
			new GoogleOAuthProperties(clientId),
			server.url("/").toString()
		);
	}

	private void enqueueTokenInfo(String audience, String emailVerified) {
		server.enqueue(new MockResponse()
			.setHeader("Content-Type", "application/json")
			.setBody("""
				{
				  "sub": "google-subject",
				  "email": "user@example.com",
				  "name": "구글 사용자",
				  "aud": "%s",
				  "iss": "https://accounts.google.com",
				  "email_verified": "%s"
				}
				""".formatted(audience, emailVerified)));
	}

	private void assertTokenInvalid(Runnable action) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.TOKEN_INVALID)
			);
	}
}
