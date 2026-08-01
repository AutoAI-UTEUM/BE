package io.edupilot.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.auth.RefreshTokenService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.user.dto.UpdateProfileRequest;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private RefreshTokenService refreshTokenService;

	@Mock
	private UserWithdrawalHook withdrawalHook;

	@Mock
	private FileStorage fileStorage;

	private BCryptPasswordEncoder passwordEncoder;
	private UserService userService;
	private User user;

	@BeforeEach
	void setUp() {
		passwordEncoder = new BCryptPasswordEncoder();
		userService = new UserService(
			userRepository,
			passwordEncoder,
			refreshTokenService,
			List.of(withdrawalHook),
			fileStorage
		);
		user = User.create(
			"user@example.com",
			passwordEncoder.encode("password123"),
			"홍길동"
		);
		ReflectionTestUtils.setField(user, "id", 1L);
		lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(user));
	}

	@Test
	void profileUpdateSupportsPartialChangesAndAffiliationClear() {
		var updated = userService.updateProfile(
			1L,
			new UpdateProfileRequest(" 새 이름 ", " EduPilot University ")
		);

		assertThat(updated.name()).isEqualTo("새 이름");
		assertThat(updated.affiliation()).isEqualTo("EduPilot University");

		var cleared = userService.updateProfile(
			1L,
			new UpdateProfileRequest(null, "  ")
		);
		assertThat(cleared.name()).isEqualTo("새 이름");
		assertThat(cleared.affiliation()).isNull();
	}

	@Test
	void profileUpdateRejectsEmptyRequestAndBlankName() {
		assertBusinessError(
			() -> userService.updateProfile(1L, new UpdateProfileRequest(null, null)),
			ErrorCode.VALIDATION_FAILED
		);
		assertBusinessError(
			() -> userService.updateProfile(1L, new UpdateProfileRequest("  ", null)),
			ErrorCode.VALIDATION_FAILED
		);
	}

	@Test
	void avatarUploadValidatesMagicAndReplacesPreviousFile() {
		ReflectionTestUtils.setField(user, "avatarKey", "avatars/old-avatar.png");
		when(fileStorage.storeAvatar(org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq("png")))
			.thenReturn("avatars/new-avatar.png");
		MockMultipartFile file = new MockMultipartFile(
			"file",
			"avatar.png",
			"image/png",
			pngBytes()
		);

		var response = userService.uploadAvatar(1L, file);

		assertThat(response.avatarUrl()).isEqualTo("/api/users/me/avatar");
		assertThat(user.getAvatarKey()).isEqualTo("avatars/new-avatar.png");
		verify(fileStorage).delete("avatars/old-avatar.png");
	}

	@Test
	void avatarUploadRejectsWrongMagicAndOversizedFile() {
		assertBusinessError(
			() -> userService.uploadAvatar(1L, new MockMultipartFile(
				"file",
				"avatar.png",
				"image/png",
				"not-png".getBytes()
			)),
			ErrorCode.VALIDATION_FAILED
		);
		assertBusinessError(
			() -> userService.uploadAvatar(1L, new MockMultipartFile(
				"file",
				"avatar.png",
				"image/png",
				new byte[2 * 1024 * 1024 + 1]
			)),
			ErrorCode.FILE_TOO_LARGE
		);
		verify(fileStorage, never()).storeAvatar(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.anyString()
		);
	}

	@Test
	void avatarDeleteRemovesFileAndIsIdempotent() {
		ReflectionTestUtils.setField(user, "avatarKey", "avatars/avatar.webp");

		userService.deleteAvatar(1L);
		userService.deleteAvatar(1L);

		assertThat(user.getAvatarKey()).isNull();
		verify(fileStorage).delete("avatars/avatar.webp");
	}

	@Test
	void withdrawalAnonymizesUserAndInvokesHooksAndTokenRevocation() {
		ReflectionTestUtils.setField(user, "avatarKey", "avatars/avatar.png");
		userService.withdraw(1L, "password123");

		assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
		assertThat(user.getEmail()).isEqualTo("deleted_1");
		assertThat(user.getName()).isEqualTo("탈퇴 사용자");
		assertThat(user.getAvatarKey()).isNull();
		assertThat(user.getPasswordHash()).isEqualTo("!withdrawn:1");
		verify(fileStorage).delete("avatars/avatar.png");
		InOrder order = inOrder(userRepository, withdrawalHook, refreshTokenService);
		order.verify(userRepository).findById(1L);
		order.verify(userRepository).flush();
		order.verify(withdrawalHook).onWithdraw(1L);
		order.verify(refreshTokenService).revokeAll(1L);
	}

	@Test
	void withdrawalRejectsWrongPasswordWithoutChangingUser() {
		assertThatThrownBy(() -> userService.withdraw(1L, "wrong"))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.INVALID_CREDENTIALS)
			);

		assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
		verify(userRepository).findById(1L);
	}

	private byte[] pngBytes() {
		return new byte[] {
			(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
			0x00, 0x00, 0x00, 0x00
		};
	}

	private void assertBusinessError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}
}
