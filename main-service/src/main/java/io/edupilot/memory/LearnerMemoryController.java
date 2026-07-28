package io.edupilot.memory;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.memory.dto.LearnerMemoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/users/me/memory")
@Validated
@Tag(name = "Learner Memory")
@SecurityRequirement(name = "bearerAuth")
public class LearnerMemoryController {

	private final LearnerMemoryService memoryService;

	public LearnerMemoryController(LearnerMemoryService memoryService) {
		this.memoryService = memoryService;
	}

	@GetMapping
	@Operation(summary = "자료별 학습자 메모리 공개 요약 조회")
	public ApiResponse<LearnerMemoryResponse> get(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@RequestParam @Positive Long materialId
	) {
		return ApiResponse.success(
			memoryService.get(authenticatedUser.userId(), materialId)
		);
	}
}
