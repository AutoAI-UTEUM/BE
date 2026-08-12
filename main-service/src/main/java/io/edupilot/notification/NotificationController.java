package io.edupilot.notification;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.notification.dto.NotificationListResponse;
import io.edupilot.notification.dto.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/users/me/notifications")
@Validated
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

	private final NotificationService notificationService;

	public NotificationController(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	@GetMapping
	@Operation(summary = "내 인앱 알림 목록 조회")
	public ApiResponse<NotificationListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(notificationService.list(
			user.userId(), page, size
		));
	}

	@PatchMapping("/{notificationId}/read")
	@Operation(summary = "내 인앱 알림 읽음 처리")
	public ApiResponse<NotificationResponse> read(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long notificationId
	) {
		return ApiResponse.success(notificationService.read(
			user.userId(), notificationId
		));
	}

	@DeleteMapping("/{notificationId}")
	@Operation(summary = "내 인앱 알림 삭제")
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long notificationId
	) {
		notificationService.delete(user.userId(), notificationId);
		return ApiResponse.success(null);
	}
}
