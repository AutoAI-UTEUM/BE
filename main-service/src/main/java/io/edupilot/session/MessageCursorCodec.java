package io.edupilot.session;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.springframework.stereotype.Component;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Component
public class MessageCursorCodec {

	public String encode(Instant createdAt, Long messageId) {
		String value = createdAt.getEpochSecond()
			+ ":" + createdAt.getNano()
			+ ":" + messageId;
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public Cursor decode(String cursor) {
		try {
			String value = new String(
				Base64.getUrlDecoder().decode(cursor),
				StandardCharsets.UTF_8
			);
			String[] parts = value.split(":", -1);
			if (parts.length != 3) {
				throw new IllegalArgumentException("Invalid cursor parts");
			}
			long epochSecond = Long.parseLong(parts[0]);
			int nano = Integer.parseInt(parts[1]);
			long messageId = Long.parseLong(parts[2]);
			if (nano < 0 || nano > 999_999_999 || messageId < 1) {
				throw new IllegalArgumentException("Invalid cursor values");
			}
			return new Cursor(Instant.ofEpochSecond(epochSecond, nano), messageId);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	public record Cursor(Instant createdAt, Long messageId) {
	}
}
