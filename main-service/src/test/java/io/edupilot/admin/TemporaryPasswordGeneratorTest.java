package io.edupilot.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.edupilot.auth.dto.SignupRequest;
import io.edupilot.auth.dto.SignupRole;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class TemporaryPasswordGeneratorTest {

	private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();
	private final Validator validator = Validation.buildDefaultValidatorFactory()
		.getValidator();

	@Test
	void everyGeneratedPasswordSatisfiesTheSignupPasswordPolicy() {
		for (int attempt = 0; attempt < 100; attempt++) {
			String password = generator.generate();
			SignupRequest request = new SignupRequest(
				"learner@example.com",
				password,
				"학습자",
				SignupRole.LEARNER,
				null,
				false,
				null
			);

			assertThat(password).hasSize(16);
			assertThat(validator.validateProperty(request, "password")).isEmpty();
			assertThat(password).matches(".*[A-Z].*");
			assertThat(password).matches(".*[a-z].*");
			assertThat(password).matches(".*[0-9].*");
			assertThat(password).matches(".*[!@#$%^&*()_+\\-=].*");
		}
	}
}
