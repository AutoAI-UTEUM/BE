package io.edupilot.material;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.material.dto.MaterialDetailResponse;
import io.edupilot.material.dto.DocChatRequest;
import io.edupilot.material.dto.DocChatResponse;
import io.edupilot.material.dto.MaterialListResponse;
import io.edupilot.material.dto.MaterialOverviewResponse;
import io.edupilot.material.dto.MaterialSummaryResponse;
import io.edupilot.material.dto.UpdateMaterialRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/materials")
@Validated
@Tag(name = "Materials")
@SecurityRequirement(name = "bearerAuth")
public class MaterialController {

	private final MaterialService materialService;
	private final MaterialOverviewService overviewService;
	private final DocChatService docChatService;

	public MaterialController(
		MaterialService materialService,
		MaterialOverviewService overviewService,
		DocChatService docChatService
	) {
		this.materialService = materialService;
		this.overviewService = overviewService;
		this.docChatService = docChatService;
	}

	@PostMapping("/{materialId}/doc-chat")
	@Operation(summary = "자료 뷰어 질문")
	public ApiResponse<DocChatResponse> docChat(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long materialId,
		@Valid @org.springframework.web.bind.annotation.RequestBody
		DocChatRequest request
	) {
		return ApiResponse.success(docChatService.askMaterial(
			authenticatedUser.userId(),
			materialId,
			request
		));
	}

	@PostMapping("/{materialId}/quiz-chat")
	@Operation(summary = "제출 퀴즈 복습 질문")
	public ApiResponse<DocChatResponse> quizChat(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long materialId,
		@Valid @org.springframework.web.bind.annotation.RequestBody
		DocChatRequest request
	) {
		return ApiResponse.success(docChatService.askQuiz(
			authenticatedUser.userId(),
			materialId,
			request
		));
	}

	@GetMapping("/{materialId}/overview")
	@Operation(summary = "학습 자료 개요 조회")
	public ApiResponse<MaterialOverviewResponse> overview(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long materialId
	) {
		return ApiResponse.success(overviewService.get(
			authenticatedUser.userId(),
			materialId
		));
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "PDF 학습 자료 업로드")
	@RequestBody(
		required = true,
		content = @Content(
			mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
			schema = @Schema(
				type = "object",
				requiredProperties = {"file", "title"}
			),
			schemaProperties = {
				@SchemaProperty(
					name = "file",
					schema = @Schema(type = "string", format = "binary")
				),
				@SchemaProperty(
					name = "title",
					schema = @Schema(type = "string")
				),
				@SchemaProperty(
					name = "classroomId",
					schema = @Schema(type = "integer", format = "int64")
				),
				@SchemaProperty(
					name = "weekNumber",
					schema = @Schema(type = "integer", format = "int32")
				)
			}
		)
	)
	public ApiResponse<MaterialSummaryResponse> upload(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@RequestPart("file") MultipartFile file,
		@Parameter(hidden = true) @RequestParam("title") String title,
		@Parameter(hidden = true)
		@RequestParam(value = "classroomId", required = false) Long classroomId,
		@Parameter(hidden = true)
		@RequestParam(value = "weekNumber", required = false) Integer weekNumber
	) {
		return ApiResponse.success(
			materialService.upload(
				authenticatedUser.userId(),
				authenticatedUser.role(),
				file,
				title,
				classroomId,
				weekNumber
			)
		);
	}

	@GetMapping
	@Operation(summary = "내 학습 자료 목록 조회")
	public ApiResponse<MaterialListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) int size
	) {
		return ApiResponse.success(
			materialService.list(authenticatedUser.userId(), page, size)
		);
	}

	@GetMapping("/{materialId}")
	@Operation(summary = "학습 자료 상세 조회")
	public ApiResponse<MaterialDetailResponse> detail(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long materialId
	) {
		return ApiResponse.success(
			materialService.detail(authenticatedUser.userId(), materialId)
		);
	}

	@PatchMapping("/{materialId}")
	@Operation(summary = "학습 자료 제목 수정")
	public ApiResponse<MaterialDetailResponse> rename(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long materialId,
		@org.springframework.web.bind.annotation.RequestBody
		UpdateMaterialRequest request
	) {
		return ApiResponse.success(materialService.rename(
			authenticatedUser.userId(),
			materialId,
			request.title()
		));
	}

	@GetMapping("/{materialId}/file")
	@Operation(summary = "학습 자료 PDF 조회")
	public ResponseEntity<org.springframework.core.io.Resource> file(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long materialId
	) {
		MaterialFile file = materialService.file(
			authenticatedUser.userId(),
			materialId
		);
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.cacheControl(CacheControl.noStore().cachePrivate())
			.header(
				HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.inline()
					.filename("material-" + file.materialId() + ".pdf")
					.build()
					.toString()
			)
			.body(file.resource());
	}

	@DeleteMapping("/{materialId}")
	@Operation(summary = "학습 자료 논리 삭제")
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long materialId
	) {
		materialService.delete(authenticatedUser.userId(), materialId);
		return ApiResponse.success(null);
	}
}
