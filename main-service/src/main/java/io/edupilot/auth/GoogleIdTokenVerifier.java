package io.edupilot.auth;

import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Component
public class GoogleIdTokenVerifier {

	private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);
	private static final String TOKEN_INFO_BASE_URL = "https://oauth2.googleapis.com";
	private static final Set<String> ALLOWED_ISSUERS = Set.of(
		"accounts.google.com",
		"https://accounts.google.com"
	);
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final GoogleOAuthProperties properties;
	private final RestClient restClient;

	@Autowired
	public GoogleIdTokenVerifier(GoogleOAuthProperties properties) {
		this(properties, TOKEN_INFO_BASE_URL);
	}

	GoogleIdTokenVerifier(GoogleOAuthProperties properties, String baseUrl) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory =
			new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(TIMEOUT);
		requestFactory.setReadTimeout(TIMEOUT);
		this.restClient = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.build();
	}

	public GoogleProfile verify(String idToken) {
		if (!StringUtils.hasText(properties.clientId())) {
			log.error("Google OAuth client ID is not configured");
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}

		GoogleTokenInfo tokenInfo;
		try {
			tokenInfo = restClient.get()
				.uri(uriBuilder -> uriBuilder
					.path("/tokeninfo")
					.queryParam("id_token", idToken)
					.build())
				.retrieve()
				.body(GoogleTokenInfo.class);
		} catch (RestClientException exception) {
			throw invalidToken();
		}

		if (tokenInfo == null
			|| !properties.clientId().equals(tokenInfo.aud())
			|| !ALLOWED_ISSUERS.contains(tokenInfo.iss())
			|| !"true".equals(tokenInfo.email_verified())
			|| !StringUtils.hasText(tokenInfo.sub())
			|| !StringUtils.hasText(tokenInfo.email())
			|| !StringUtils.hasText(tokenInfo.name())) {
			throw invalidToken();
		}

		return new GoogleProfile(
			tokenInfo.sub(),
			tokenInfo.email(),
			tokenInfo.name()
		);
	}

	private BusinessException invalidToken() {
		return new BusinessException(ErrorCode.TOKEN_INVALID);
	}

	private record GoogleTokenInfo(
		String sub,
		String email,
		String name,
		String aud,
		String iss,
		String email_verified
	) {
	}
}
