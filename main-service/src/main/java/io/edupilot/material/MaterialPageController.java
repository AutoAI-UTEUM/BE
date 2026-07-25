package io.edupilot.material;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.material.dto.MaterialPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/materials")
@ConditionalOnProperty(
	prefix = "edupilot.material",
	name = "page-text-api-enabled",
	havingValue = "true"
)
@Tag(name = "Materials (Development)")
@SecurityRequirement(name = "bearerAuth")
public class MaterialPageController {

	private final MaterialService materialService;

	public MaterialPageController(MaterialService materialService) {
		this.materialService = materialService;
	}

	@GetMapping("/{materialId}/pages/{pageNumber}")
	@Operation(summary = "추출된 페이지 텍스트 조회 (local/dev 전용)")
	public ApiResponse<MaterialPageResponse> page(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long materialId,
		@PathVariable int pageNumber
	) {
		return ApiResponse.success(
			materialService.page(
				authenticatedUser.userId(),
				materialId,
				pageNumber
			)
		);
	}
}
