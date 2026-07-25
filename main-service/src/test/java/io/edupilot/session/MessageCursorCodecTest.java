package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

class MessageCursorCodecTest {

	private final MessageCursorCodec codec = new MessageCursorCodec();

	@Test
	void roundTripsCompositeCursor() {
		Instant createdAt = Instant.parse("2026-07-25T10:00:00.123456Z");

		String encoded = codec.encode(createdAt, 498L);
		MessageCursorCodec.Cursor decoded = codec.decode(encoded);

		assertThat(decoded.createdAt()).isEqualTo(createdAt);
		assertThat(decoded.messageId()).isEqualTo(498L);
	}

	@Test
	void rejectsInvalidBase64AndFieldsAsValidationFailure() {
		assertThatThrownBy(() -> codec.decode("%%%"))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.VALIDATION_FAILED)
			);
		assertThatThrownBy(() -> codec.decode(
			java.util.Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString("1:2".getBytes())
		)).isInstanceOf(BusinessException.class);
	}
}
