package io.edupilot.user;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.edupilot.auth.RefreshTokenService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.material.storage.StorageException;
import io.edupilot.user.dto.AvatarResponse;
import io.edupilot.user.dto.UpdateProfileRequest;
import io.edupilot.user.dto.UpdatePreferencesRequest;
import io.edupilot.user.dto.UserPreferencesResponse;
import io.edupilot.user.dto.UserResponse;

@Service
public class UserService {
	private static final long AVATAR_MAX_BYTES = 2L * 1024 * 1024;
	private static final byte[] JPEG_MAGIC = {(byte)0xFF, (byte)0xD8, (byte)0xFF};
	private static final byte[] PNG_MAGIC = {
		(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
	};
	private static final Map<String, AvatarType> AVATAR_TYPES = Map.of(
		MediaType.IMAGE_JPEG_VALUE,
		new AvatarType("jpg", MediaType.IMAGE_JPEG),
		MediaType.IMAGE_PNG_VALUE,
		new AvatarType("png", MediaType.IMAGE_PNG),
		"image/webp",
		new AvatarType("webp", MediaType.parseMediaType("image/webp"))
	);

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokenService;
	private final List<UserWithdrawalHook> withdrawalHooks;
	private final FileStorage fileStorage;

	public UserService(
		UserRepository userRepository,
		PasswordEncoder passwordEncoder,
		RefreshTokenService refreshTokenService,
		List<UserWithdrawalHook> withdrawalHooks,
		FileStorage fileStorage
	) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokenService = refreshTokenService;
		this.withdrawalHooks = withdrawalHooks;
		this.fileStorage = fileStorage;
	}

	@Transactional(readOnly = true)
	public UserResponse me(Long userId) {
		User user = activeUser(userId);
		return UserResponse.from(user);
	}

	@Transactional
	public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
		if (request.name() == null && request.affiliation() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		User user = activeUser(userId);
		String name = request.name() == null ? null : request.name().trim();
		if (name != null && name.isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		String affiliation = request.affiliation() == null
			? user.getAffiliation()
			: normalizeOptional(request.affiliation());
		user.updateProfile(name, affiliation);
		return UserResponse.from(user);
	}

	@Transactional(readOnly = true)
	public UserPreferencesResponse preferences(Long userId) {
		return UserPreferencesResponse.from(activeUser(userId));
	}

	@Transactional
	public UserPreferencesResponse updatePreferences(
		Long userId,
		UpdatePreferencesRequest request
	) {
		if (request.newMaterialNotification() == null
			&& request.studyReminder() == null
			&& request.aiAnswerStyle() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		User user = activeUser(userId);
		user.updatePreferences(
			request.newMaterialNotification(),
			request.studyReminder(),
			request.aiAnswerStyle()
		);
		return UserPreferencesResponse.from(user);
	}

	@Transactional
	public AvatarResponse uploadAvatar(Long userId, MultipartFile file) {
		AvatarType type = validateAvatar(file);
		String newKey;
		try (InputStream inputStream = file.getInputStream()) {
			newKey = fileStorage.storeAvatar(inputStream, type.extension());
		} catch (IOException exception) {
			throw new StorageException("업로드 파일을 읽을 수 없습니다.", exception);
		}

		try {
			User user = activeUser(userId);
			String oldKey = user.getAvatarKey();
			user.replaceAvatar(newKey);
			userRepository.flush();
			if (oldKey != null) {
				fileStorage.delete(oldKey);
			}
			return new AvatarResponse(user.getAvatarUrl());
		} catch (RuntimeException exception) {
			try {
				fileStorage.delete(newKey);
			} catch (RuntimeException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public UserAvatar avatar(Long userId) {
		User user = activeUser(userId);
		String avatarKey = user.getAvatarKey();
		if (avatarKey == null) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		AvatarType type = avatarTypeFromKey(avatarKey);
		return new UserAvatar(
			fileStorage.load(avatarKey),
			type.mediaType(),
			type.extension()
		);
	}

	@Transactional
	public void deleteAvatar(Long userId) {
		User user = activeUser(userId);
		String avatarKey = user.getAvatarKey();
		if (avatarKey == null) {
			return;
		}
		user.replaceAvatar(null);
		userRepository.flush();
		fileStorage.delete(avatarKey);
	}

	@Transactional
	public void withdraw(Long userId, String password) {
		User user = activeUser(userId);
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}

		String avatarKey = user.getAvatarKey();
		user.withdraw();
		userRepository.flush();
		if (avatarKey != null) {
			fileStorage.delete(avatarKey);
		}
		withdrawalHooks.forEach(hook -> hook.onWithdraw(userId));
		refreshTokenService.revokeAll(userId);
	}

	private User activeUser(Long userId) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		return user;
	}

	private AvatarType validateAvatar(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (file.getSize() > AVATAR_MAX_BYTES) {
			throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
		}
		AvatarType type = AVATAR_TYPES.get(file.getContentType());
		if (type == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		try (InputStream inputStream = file.getInputStream()) {
			byte[] header = inputStream.readNBytes(12);
			if (!matchesMagic(type.extension(), header)) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
		} catch (IOException exception) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return type;
	}

	private boolean matchesMagic(String extension, byte[] header) {
		return switch (extension) {
			case "jpg" -> startsWith(header, JPEG_MAGIC);
			case "png" -> startsWith(header, PNG_MAGIC);
			case "webp" -> header.length >= 12
				&& Arrays.equals(
					Arrays.copyOfRange(header, 0, 4),
					"RIFF".getBytes(StandardCharsets.US_ASCII)
				)
				&& Arrays.equals(
					Arrays.copyOfRange(header, 8, 12),
					"WEBP".getBytes(StandardCharsets.US_ASCII)
				);
			default -> false;
		};
	}

	private boolean startsWith(byte[] value, byte[] prefix) {
		return value.length >= prefix.length
			&& Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
	}

	private String normalizeOptional(String value) {
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private AvatarType avatarTypeFromKey(String avatarKey) {
		return AVATAR_TYPES.values().stream()
			.filter(type -> avatarKey.endsWith("." + type.extension()))
			.findFirst()
			.orElseThrow(() -> new StorageException("유효하지 않은 아바타 키입니다."));
	}

	private record AvatarType(String extension, MediaType mediaType) {
	}
}
