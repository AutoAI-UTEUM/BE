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
import io.edupilot.mail.EmailService;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailTemplates;
import io.edupilot.mail.MailProperties;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.user.dto.UpdateProfileRequest;
import io.edupilot.user.dto.UpdatePreferencesRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
	@Mock private io.edupilot.deletion.DeletionJournal deletionJournal;
	@Mock private EntityManager entityManager;

	@Mock
	private UserRepository userRepository;

	@Mock
	private RefreshTokenService refreshTokenService;

	@Mock
	private UserWithdrawalHook withdrawalHook;

	@Mock
	private FileStorage fileStorage;

	@Mock
	private PasswordChangeAttemptLimiter passwordChangeAttemptLimiter;
	@Mock private EmailService emailService;

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
			fileStorage,
			passwordChangeAttemptLimiter,
			emailService,
			new EmailTemplates(new MailProperties(true, "logging", "test@example.com", "", "https://dev.uteum.com", "ap-northeast-2")),
			deletionJournal,
			entityManager
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
	void passwordChangeUpdatesHashRevokesEveryRefreshTokenAndResetsFailures() {
		var response = userService.changePassword(
			1L,
			"password123",
			"newPassword456"
		);

		assertThat(passwordEncoder.matches(
			"newPassword456",
			user.getPasswordHash()
		)).isTrue();
		assertThat(response.reauthenticationRequired()).isTrue();
		verify(refreshTokenService).revokeAll(1L);
		verify(passwordChangeAttemptLimiter).reset(1L);
	}

	@Test
	void passwordChangeRecordsCurrentPasswordMismatch() {
		assertBusinessError(
			() -> userService.changePassword(1L, "wrong", "newPassword456"),
			ErrorCode.CURRENT_PASSWORD_MISMATCH
		);

		verify(passwordChangeAttemptLimiter).recordFailure(1L);
		verify(refreshTokenService, never()).revokeAll(1L);
	}

	@Test
	void passwordChangeRejectsGoogleAccountAndPasswordReuse() {
		User googleUser = User.createGoogle(
			"google@example.com",
			"!google-account",
			"구글 사용자",
			UserRole.LEARNER,
			null,
			false,
			null,
			null,
			null,
			"google-sub"
		);
		ReflectionTestUtils.setField(googleUser, "id", 2L);
		when(userRepository.findById(2L)).thenReturn(Optional.of(googleUser));

		assertBusinessError(
			() -> userService.changePassword(2L, "unused", "newPassword456"),
			ErrorCode.PASSWORD_NOT_SUPPORTED
		);
		assertBusinessError(
			() -> userService.changePassword(1L, "password123", "password123"),
			ErrorCode.PASSWORD_REUSE_NOT_ALLOWED
		);

		verify(refreshTokenService, never()).revokeAll(org.mockito.ArgumentMatchers.anyLong());
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
	void preferencesReturnDefaultsAndSupportPartialUpdates() {
		var defaults = userService.preferences(1L);
		assertThat(defaults.newMaterialNotification()).isTrue();
		assertThat(defaults.studyReminder()).isTrue();
		assertThat(defaults.aiAnswerStyle()).isEqualTo(AiAnswerStyle.NORMAL);

		var updated = userService.updatePreferences(
			1L,
			new UpdatePreferencesRequest(false, null, AiAnswerStyle.DETAILED)
		);
		assertThat(updated.newMaterialNotification()).isFalse();
		assertThat(updated.studyReminder()).isTrue();
		assertThat(updated.aiAnswerStyle()).isEqualTo(AiAnswerStyle.DETAILED);
	}

	@Test
	void preferencesRejectEmptyPatch() {
		assertBusinessError(
			() -> userService.updatePreferences(
				1L,
				new UpdatePreferencesRequest(null, null, null)
			),
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
		verify(deletionJournal).recordAvatar("avatars/avatar.png");
		InOrder order = inOrder(userRepository, entityManager, withdrawalHook, refreshTokenService);
		order.verify(userRepository).findById(1L);
		order.verify(entityManager).refresh(user, LockModeType.PESSIMISTIC_WRITE);
		order.verify(userRepository).flush();
		order.verify(withdrawalHook).onWithdraw(1L);
		order.verify(refreshTokenService).revokeAll(1L);
		var message = org.mockito.ArgumentCaptor.forClass(EmailMessage.class);
		verify(emailService).sendAsync(message.capture());
		assertThat(message.getValue().to()).isEqualTo("user@example.com");
		assertThat(message.getValue().textBody()).contains("계정을 더 이상 이용할 수 없습니다");
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
		verify(entityManager).refresh(user, LockModeType.PESSIMISTIC_WRITE);
		verify(emailService, never()).sendAsync(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void googleWithdrawalRequiresProviderAndExactVerifiedSubject() {
		User google = User.createGoogle("google@example.com", passwordEncoder.encode("password123"),
			"Synthetic", UserRole.LEARNER, null, false, null, null, null, "actual-google-sub");
		ReflectionTestUtils.setField(google, "id", 2L);
		when(userRepository.findById(2L)).thenReturn(Optional.of(google));
		assertBusinessError(() -> userService.withdraw(2L, "password123"), ErrorCode.INVALID_CREDENTIALS);
		assertBusinessError(() -> userService.withdrawGoogle(1L, "actual-google-sub"), ErrorCode.INVALID_CREDENTIALS);
		assertBusinessError(() -> userService.withdrawGoogle(2L, "other-google-sub"), ErrorCode.INVALID_CREDENTIALS);
		assertThat(google.isActive()).isTrue();
		userService.withdrawGoogle(2L, "actual-google-sub");
		assertThat(google.isActive()).isFalse();
		verify(refreshTokenService).revokeAll(2L);
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
