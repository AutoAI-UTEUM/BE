package io.edupilot.classroom;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.classroom.dto.ClassroomAnalyticsResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/classrooms/{id}/analytics")
@Tag(name = "Classrooms")
@SecurityRequirement(name = "bearerAuth")
public class ClassroomAnalyticsController {

	private final ClassroomAnalyticsService analyticsService;

	public ClassroomAnalyticsController(ClassroomAnalyticsService analyticsService) {
		this.analyticsService = analyticsService;
	}

	@GetMapping
	@Operation(summary = "강의자 학습 현황 조회")
	public ApiResponse<ClassroomAnalyticsResponse> getAnalytics(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId
	) {
		return ApiResponse.success(analyticsService.getAnalytics(
			user.userId(),
			user.role(),
			classroomId
		));
	}
}
