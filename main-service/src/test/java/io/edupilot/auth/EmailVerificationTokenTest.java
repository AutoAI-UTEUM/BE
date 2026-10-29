package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

class EmailVerificationTokenTest {
	@Test void expiryIsExclusiveAndEmailBindingAndOneUseAreRequired() {
		Instant now=Instant.parse("2026-10-03T00:00:00Z");
		var user=User.create("synthetic@example.com","hash","Synthetic",UserRole.LEARNER);
		var token=EmailVerificationToken.create(user,"token-hash","email-hash",now,now.plusSeconds(1800));
		assertThat(token.isUsable(now.plusSeconds(1799),"email-hash")).isTrue();
		assertThat(token.isUsable(now.plusSeconds(1800),"email-hash")).isFalse();
		assertThat(token.isUsable(now,"different-email")).isFalse();
		token.use(now.plusSeconds(1));
		assertThat(token.isUsable(now.plusSeconds(2),"email-hash")).isFalse();
	}
}
