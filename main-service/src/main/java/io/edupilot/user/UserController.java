package io.edupilot.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
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
