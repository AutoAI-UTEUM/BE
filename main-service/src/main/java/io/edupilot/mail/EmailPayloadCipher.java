package io.edupilot.mail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.edupilot.auth.JwtProperties;
import tools.jackson.databind.ObjectMapper;

@Component
public class EmailPayloadCipher {
	private static final int NONCE_BYTES = 12;
	private static final int MAX_PLAINTEXT_BYTES = 128 * 1024;
	private final SecretKeySpec key;
	private final ObjectMapper json;
	private final SecureRandom random = new SecureRandom();

	public EmailPayloadCipher(EmailOutboxProperties properties, JwtProperties jwt, ObjectMapper json) {
		this.json = json;
		try {
			byte[] secret;
			if (StringUtils.hasText(properties.encryptionKey())) {
				secret = Base64.getDecoder().decode(properties.encryptionKey());
				if (secret.length != 32) {
					throw new IllegalArgumentException();
				}
			} else {
				byte[] signingKey = Base64.getDecoder().decode(jwt.secret());
				if (signingKey.length < 32) {
					throw new IllegalArgumentException();
				}
				// HKDF-SHA256 with a mail-specific domain; never use the signing key directly for AES.
				Mac extract = Mac.getInstance("HmacSHA256");
				extract.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
				Mac expand = Mac.getInstance("HmacSHA256");
				expand.init(new SecretKeySpec(extract.doFinal(signingKey), "HmacSHA256"));
				expand.update("edupilot/mail-outbox/aes-gcm/v1".getBytes(StandardCharsets.UTF_8));
				secret = expand.doFinal(new byte[] {1});
			}
			key = new SecretKeySpec(secret, "AES");
		} catch (GeneralSecurityException | IllegalArgumentException error) {
			// Configuration exceptions must not include key material.
			throw new IllegalStateException("Invalid mail outbox encryption key configuration");
		}
	}

	public byte[] encrypt(Long deliveryId, EmailMessage message) {
		byte[] plaintext = json.writeValueAsBytes(message);
		if (plaintext.length > MAX_PLAINTEXT_BYTES) {
			throw new IllegalArgumentException("Mail payload exceeds maximum size");
		}
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, deliveryId, nonce);
			byte[] ciphertext = cipher.doFinal(plaintext);
			return ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
				.put((byte) 1).put(nonce).put(ciphertext).array();
		} catch (GeneralSecurityException error) {
			throw new IllegalStateException("Could not encrypt mail payload");
		} finally {
			java.util.Arrays.fill(plaintext, (byte) 0);
		}
	}

	public EmailMessage decrypt(Long deliveryId, byte[] envelope) {
		if (envelope == null || envelope.length < 1 + NONCE_BYTES + 16 || envelope[0] != 1) {
			throw new IllegalStateException("Unreadable mail payload");
		}
		ByteBuffer input = ByteBuffer.wrap(envelope);
		input.get();
		byte[] nonce = new byte[NONCE_BYTES];
		input.get(nonce);
		byte[] ciphertext = new byte[input.remaining()];
		input.get(ciphertext);
		try {
			byte[] plaintext = cipher(Cipher.DECRYPT_MODE, deliveryId, nonce).doFinal(ciphertext);
			try {
				return json.readValue(plaintext, EmailMessage.class);
			} finally {
				java.util.Arrays.fill(plaintext, (byte) 0);
			}
		} catch (GeneralSecurityException | RuntimeException error) {
			throw new IllegalStateException("Unreadable mail payload");
		}
	}

	private Cipher cipher(int mode, Long id, byte[] nonce) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(128, nonce));
		cipher.updateAAD(("mail-outbox-v1:" + id).getBytes(StandardCharsets.UTF_8));
		return cipher;
	}
}
