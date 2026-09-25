package io.edupilot.admin.mail;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.admin.AdminAction;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.mail.EmailDeliveryStatus;
import io.edupilot.mail.EmailDeliveryType;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/admin/mail")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin Mail")
@Validated
public class AdminMailController {

	private final EmailService emailService;
	private final AdminMailService adminMailService;
	private final AdminMailTestRateLimiter rateLimiter;

	public AdminMailController(
		EmailService emailService,
		AdminMailService adminMailService,
		AdminMailTestRateLimiter rateLimiter
	) {
		this.emailService = emailService;
		this.adminMailService = adminMailService;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/test")
	@AdminAction("ADMIN_MAIL_TEST")
	@Operation(summary = "관리자 SES 테스트 메일 발송")
	public ApiResponse<TestMailResponse> test(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody TestMailRequest request
	) {
		rateLimiter.acquire(user.userId());
		Long deliveryId = emailService.sendAsync(new EmailMessage(
			request.to(),
			"[UTEUM] 메일 발송 테스트",
			"UTEUM 시스템 메일 발송 테스트입니다.\n\n이 메일은 발신 전용입니다.",
			null,
			EmailDeliveryType.TEST
		));
		return ApiResponse.success(new TestMailResponse(deliveryId));
	}

	@GetMapping("/deliveries")
	@AdminAction("ADMIN_MAIL_DELIVERIES_VIEWED")
	@Operation(summary = "관리자 메일 발송 이력 조회")
	public ApiResponse<AdminMailDeliveryListResponse> deliveries(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
		@RequestParam(required = false) EmailDeliveryStatus status,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(adminMailService.list(from, to, status, page, size));
	}


	public record TestMailRequest(@NotBlank @Email @Size(max = 320) String to) {
	}

	public record TestMailResponse(Long deliveryId) {
	}
}
