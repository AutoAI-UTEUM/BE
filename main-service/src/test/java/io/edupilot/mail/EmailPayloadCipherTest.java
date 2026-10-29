package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.edupilot.auth.JwtProperties;
import tools.jackson.databind.json.JsonMapper;

class EmailPayloadCipherTest {
	private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	@Test void encryptedPayloadIsRandomizedAndCanBeRecoveredByANewProcessWithTheSameKey() {
		EmailMessage message = new EmailMessage("synthetic@example.com", "title", "token=synthetic-secret", "<b>private</b>", EmailDeliveryType.EMAIL_VERIFY);
		byte[] first = cipher("").encrypt(1L, message);
		byte[] second = cipher("").encrypt(1L, message);
		assertThat(first).isNotEqualTo(second);
		assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain("synthetic-secret", "synthetic@example.com", "private");
		assertThat(cipher("").decrypt(1L, first)).isEqualTo(message);
		assertThat(cipher(KEY).decrypt(1L, cipher(KEY).encrypt(1L, message))).isEqualTo(message);
	}
	@Test void tamperingOrMovingTheCiphertextToAnotherDeliveryIsRejected() {
		byte[] ciphertext = cipher("").encrypt(1L, new EmailMessage("a@example.com", "a", "secret", null, EmailDeliveryType.TEST));
		assertThatThrownBy(() -> cipher("").decrypt(2L, ciphertext)).hasMessage("Unreadable mail payload");
		ciphertext[ciphertext.length - 1] ^= 1;
		assertThatThrownBy(() -> cipher("").decrypt(1L, ciphertext)).hasMessage("Unreadable mail payload");
	}
	@Test void invalidKeyAndOversizedBodyDoNotAppearInErrors() {
		assertThatThrownBy(() -> cipher("bad-private-key")).hasMessage("Invalid mail outbox encryption key configuration");
		assertThatThrownBy(() -> cipher("").encrypt(1L,
			new EmailMessage("a@example.com", "a", "s".repeat(150000), null, EmailDeliveryType.TEST)))
			.hasMessage("Mail payload exceeds maximum size");
	}
	private EmailPayloadCipher cipher(String key) {
		return new EmailPayloadCipher(new EmailOutboxProperties(key, Duration.ofMinutes(2), Duration.ofSeconds(30), 3, 50),
			new JwtProperties(KEY, Duration.ofMinutes(15)), new JsonMapper());
	}
}
