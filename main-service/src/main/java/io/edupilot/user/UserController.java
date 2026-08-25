package io.edupilot.user;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.user.dto.AvatarResponse;
import io.edupilot.user.dto.UpdateProfileRequest;
import io.edupilot.user.dto.UpdatePreferencesRequest;
import io.edupilot.user.dto.UserPreferencesResponse;
import io.edupilot.user.dto.UserResponse;
import io.edupilot.user.dto.WithdrawRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping("/me")
	@Operation(summary = "내 정보 조회")
	public ApiResponse<UserResponse> me(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		return ApiResponse.success(userService.me(authenticatedUser.userId()));
	}

	@PatchMapping("/me")
	@Operation(summary = "내 프로필 수정")
	public ApiResponse<UserResponse> updateProfile(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody UpdateProfileRequest request
	) {
		return ApiResponse.success(userService.updateProfile(
			authenticatedUser.userId(),
			request
		));
	}

	@GetMapping("/me/preferences")
	@Operation(summary = "내 학습 환경설정 조회")
	public ApiResponse<UserPreferencesResponse> preferences(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		return ApiResponse.success(userService.preferences(authenticatedUser.userId()));
	}

	@PatchMapping("/me/preferences")
	@Operation(summary = "내 학습 환경설정 수정")
	public ApiResponse<UserPreferencesResponse> updatePreferences(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody UpdatePreferencesRequest request
	) {
		return ApiResponse.success(userService.updatePreferences(
			authenticatedUser.userId(),
			request
		));
	}

	@PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "내 아바타 업로드 또는 교체")
	public ApiResponse<AvatarResponse> uploadAvatar(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@RequestPart("file") MultipartFile file
	) {
		return ApiResponse.success(userService.uploadAvatar(
			authenticatedUser.userId(),
			file
		));
	}

	@GetMapping("/me/avatar")
	@Operation(summary = "내 아바타 조회")
	public ResponseEntity<org.springframework.core.io.Resource> avatar(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		UserAvatar avatar = userService.avatar(authenticatedUser.userId());
		return ResponseEntity.ok()
			.contentType(avatar.mediaType())
			.cacheControl(CacheControl.noStore().cachePrivate())
			.header(
				HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.inline()
					.filename("avatar." + avatar.extension())
					.build()
					.toString()
			)
			.body(avatar.resource());
	}

	@DeleteMapping("/me/avatar")
	@Operation(summary = "내 아바타 삭제")
	public ApiResponse<Void> deleteAvatar(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser
	) {
		userService.deleteAvatar(authenticatedUser.userId());
		return ApiResponse.success(null);
	}

	@DeleteMapping("/me")
	@Operation(summary = "회원 탈퇴")
	public ApiResponse<Void> withdraw(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody WithdrawRequest request
	) {
		userService.withdraw(authenticatedUser.userId(), request.password());
		return ApiResponse.success(null);
	}
}
