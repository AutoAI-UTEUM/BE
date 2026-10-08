package io.edupilot.guardian;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import io.edupilot.auth.JwtProperties;

@Component
public class GuardianWebSecrets {
	private final SecureRandom random = new SecureRandom();
	private final SecretKeySpec phoneKey;
	public GuardianWebSecrets(JwtProperties jwt) {
		phoneKey = new SecretKeySpec(Base64.getDecoder().decode(jwt.secret()), "HmacSHA256");
	}
	String token() {
		byte[] bytes = new byte[32]; random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
	static String hash(String raw) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
		catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
	}
	String phoneFingerprint(String phone) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256"); mac.init(phoneKey);
			return HexFormat.of().formatHex(mac.doFinal(("guardian-phone:v1:" + phone).getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("HMAC unavailable"); }
	}
}
