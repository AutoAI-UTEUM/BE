package io.edupilot.classroom;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

@Component
public class ClassroomInviteCodeGenerator {

	private static final char[] ALPHABET =
		"ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

	private final SecureRandom secureRandom;

	public ClassroomInviteCodeGenerator() {
		this(new SecureRandom());
	}

	ClassroomInviteCodeGenerator(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	public String generate() {
		return randomBlock() + "-" + randomBlock();
	}

	private String randomBlock() {
		StringBuilder block = new StringBuilder(4);
		for (int index = 0; index < 4; index++) {
			block.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
		}
		return block.toString();
	}
}
