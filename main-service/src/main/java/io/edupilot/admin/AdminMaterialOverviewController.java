package io.edupilot.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.material.MaterialOutlinePersistenceService;
import io.edupilot.material.MaterialOutlineTaskDispatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/materials")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin Materials")
public class AdminMaterialOverviewController {

	private final MaterialOutlinePersistenceService persistenceService;
	private final MaterialOutlineTaskDispatcher dispatcher;
	private final AdminMaterialOverviewRegenerationRateLimiter rateLimiter;

	public AdminMaterialOverviewController(
		MaterialOutlinePersistenceService persistenceService,
		MaterialOutlineTaskDispatcher dispatcher,
		AdminMaterialOverviewRegenerationRateLimiter rateLimiter
	) {
		this.persistenceService = persistenceService;
		this.dispatcher = dispatcher;
		this.rateLimiter = rateLimiter;
	}

	/** 조회 전용 원칙의 예외: 실패가 누적된 개요를 운영자가 명시적으로 재생성한다. */
	@PostMapping("/{id}/overview/regenerate")
	@AdminAction("MATERIAL_OVERVIEW_REGENERATION_REQUESTED")
	@Operation(summary = "관리자 자료 개요 비동기 재생성")
	public ResponseEntity<ApiResponse<Void>> regenerate(
		@PathVariable("id") Long materialId
	) {
		rateLimiter.acquire(materialId);
		if (!persistenceService.prepareManualRegeneration(materialId)) {
			throw new BusinessException(ErrorCode.MATERIAL_NOT_FOUND);
		}
		dispatcher.submitManual(materialId);
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.body(ApiResponse.success(null, "개요 재생성 작업을 접수했습니다."));
	}
}
