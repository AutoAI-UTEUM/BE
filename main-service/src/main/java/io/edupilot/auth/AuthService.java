package io.edupilot.auth;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.auth.RefreshTokenService.RotationResult;
import io.edupilot.auth.RefreshTokenService.RotationStatus;
import io.edupilot.auth.dto.AccessTokenResponse;
import io.edupilot.auth.dto.LoginRequest;
import io.edupilot.auth.dto.LoginResponse;
import io.edupilot.auth.dto.SignupRequest;
import io.edupilot.auth.dto.SignupResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.dto.UserResponse;

@Service
public class AuthService {

	private static final String TOKEN_TYPE = "Bearer";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;
	private final RefreshTokenService refreshTokenService;

	public AuthService(
		UserRepository userRepository,
		PasswordEncoder passwordEncoder,
		JwtTokenProvider jwtTokenProvider,
		RefreshTokenService refreshTokenService
	) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtTokenProvider = jwtTokenProvider;
		this.refreshTokenService = refreshTokenService;
	}

	@Transactional
	public SignupResponse signup(SignupRequest request) {
		String email = normalizeEmail(request.email());
		if (userRepository.existsByEmail(email)) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}

		User user = User.create(
			email,
			passwordEncoder.encode(request.password()),
			request.name().trim()
		);
		try {
			User savedUser = userRepository.saveAndFlush(user);
			return new SignupResponse(
				savedUser.getId(),
				savedUser.getEmail(),
				savedUser.getName()
			);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}
	}

	@Transactional
	public LoginResult login(LoginRequest request) {
		User user = userRepository.findByEmail(normalizeEmail(request.email()))
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}

		String accessToken = jwtTokenProvider.createAccessToken(user);
		String refreshToken = refreshTokenService.issue(user);
		LoginResponse response = new LoginResponse(
			accessToken,
			TOKEN_TYPE,
			jwtTokenProvider.accessTokenExpiresInSeconds(),
			UserResponse.from(user)
		);
		return new LoginResult(response, refreshToken);
	}

	public RefreshResult refresh(String rawToken) {
		RotationResult rotation = refreshTokenService.rotate(rawToken);
		if (rotation.status() == RotationStatus.INACTIVE) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		if (rotation.status() != RotationStatus.SUCCESS) {
			throw new BusinessException(ErrorCode.TOKEN_INVALID);
		}

		String accessToken = jwtTokenProvider.createAccessToken(rotation.user());
		AccessTokenResponse response = new AccessTokenResponse(
			accessToken,
			TOKEN_TYPE,
			jwtTokenProvider.accessTokenExpiresInSeconds()
		);
		return new RefreshResult(response, rotation.rawToken());
	}

	public void logout(String rawToken) {
		refreshTokenService.logout(rawToken);
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	public record LoginResult(LoginResponse response, String refreshToken) {
	}

	public record RefreshResult(AccessTokenResponse response, String refreshToken) {
	}
}
