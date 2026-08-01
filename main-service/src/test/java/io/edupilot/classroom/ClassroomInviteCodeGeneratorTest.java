package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClassroomInviteCodeGeneratorTest {

	@Test
	void generatesReadableUppercaseCode() {
		ClassroomInviteCodeGenerator generator = new ClassroomInviteCodeGenerator();

		for (int index = 0; index < 100; index++) {
			assertThat(generator.generate())
				.matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}")
				.doesNotContain("O", "0", "I", "1");
		}
	}
}
