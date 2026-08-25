package io.edupilot.classroom;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
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
import io.edupilot.classroom.dto.ClassroomResourceListResponse;
import io.edupilot.classroom.dto.ClassroomResourceResponse;
import io.edupilot.classroom.dto.CreateClassroomLinkResourceRequest;
import io.edupilot.classroom.dto.UpdateClassroomResourceRequest;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Classroom Resources")
@SecurityRequirement(name = "bearerAuth")
public class ClassroomResourceController {

	private static final String X_CONTENT_TYPE_OPTIONS =
		"X-Content-Type-Options";
	private static final Map<String, MediaType> DOWNLOAD_MEDIA_TYPES = Map.ofEntries(
		Map.entry("jpg", MediaType.IMAGE_JPEG),
		Map.entry("jpeg", MediaType.IMAGE_JPEG),
		Map.entry("png", MediaType.IMAGE_PNG),
		Map.entry("gif", MediaType.IMAGE_GIF),
		Map.entry("webp", MediaType.valueOf("image/webp")),
		Map.entry("pdf", MediaType.APPLICATION_PDF),
		Map.entry("doc", MediaType.valueOf("application/msword")),
		Map.entry("docx", MediaType.valueOf(
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document"
		)),
		Map.entry("ppt", MediaType.valueOf("application/vnd.ms-powerpoint")),
		Map.entry("pptx", MediaType.valueOf(
			"application/vnd.openxmlformats-officedocument.presentationml.presentation"
		)),
		Map.entry("xls", MediaType.valueOf("application/vnd.ms-excel")),
		Map.entry("xlsx", MediaType.valueOf(
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
		)),
		Map.entry("hwp", MediaType.valueOf("application/x-hwp")),
		Map.entry("hwpx", MediaType.valueOf("application/vnd.hancom.hwpx")),
		Map.entry("txt", MediaType.TEXT_PLAIN),
		Map.entry("csv", MediaType.valueOf("text/csv")),
		Map.entry("zip", MediaType.valueOf("application/zip"))
	);

	private final ClassroomResourceService resourceService;

	public ClassroomResourceController(ClassroomResourceService resourceService) {
		this.resourceService = resourceService;
	}

	@PostMapping(
		value = "/classrooms/{classroomId}/resources",
		consumes = MediaType.MULTIPART_FORM_DATA_VALUE
	)
	@Operation(
		operationId = "createClassroomFileResource",
		summary = "강의실 파일 자료 등록"
	)
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
					schema = @Schema(type = "string", maxLength = 200)
				),
				@SchemaProperty(
					name = "weekNumber",
					schema = @Schema(type = "integer", format = "int32")
				)
			}
		)
	)
	public ApiResponse<ClassroomResourceResponse> createFile(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@RequestPart("file") MultipartFile file,
		@Parameter(hidden = true) @RequestParam("title") String title,
		@Parameter(hidden = true)
		@RequestParam(value = "weekNumber", required = false) Integer weekNumber
	) {
		return ApiResponse.success(resourceService.createFile(
			user.userId(),
			user.role(),
			classroomId,
			file,
			title,
			weekNumber
		));
	}

	@PostMapping(
		value = "/classrooms/{classroomId}/resources",
		consumes = MediaType.APPLICATION_JSON_VALUE
	)
	@Operation(
		operationId = "createClassroomLinkResource",
		summary = "강의실 링크 자료 등록"
	)
	public ApiResponse<ClassroomResourceResponse> createLink(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@Valid @org.springframework.web.bind.annotation.RequestBody
		CreateClassroomLinkResourceRequest request
	) {
		return ApiResponse.success(resourceService.createLink(
			user.userId(),
			user.role(),
			classroomId,
			request
		));
	}

	@GetMapping("/classrooms/{classroomId}/resources")
	@Operation(summary = "강의실 일반 자료 목록 조회")
	public ApiResponse<ClassroomResourceListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@RequestParam(required = false) Integer weekNumber,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(resourceService.list(
			user.userId(),
			user.role(),
			classroomId,
			weekNumber,
			page,
			size
		));
	}

	@PatchMapping("/resources/{resourceId}")
	@Operation(summary = "강의실 일반 자료 수정")
	public ApiResponse<ClassroomResourceResponse> update(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long resourceId,
		@org.springframework.web.bind.annotation.RequestBody
		UpdateClassroomResourceRequest request
	) {
		return ApiResponse.success(resourceService.update(
			user.userId(),
			user.role(),
			resourceId,
			request
		));
	}

	@GetMapping("/resources/{resourceId}/file")
	@Operation(summary = "강의실 파일 자료 다운로드")
	public ResponseEntity<org.springframework.core.io.Resource> file(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long resourceId
	) {
		ClassroomResourceFile file = resourceService.file(
			user.userId(),
			user.role(),
			resourceId
		);
		ContentDisposition disposition = (file.inline()
			? ContentDisposition.inline()
			: ContentDisposition.attachment())
			.filename(file.fileName(), StandardCharsets.UTF_8)
			.build();
		return ResponseEntity.ok()
			.contentType(downloadContentType(file.fileName()))
			.header(
				HttpHeaders.CACHE_CONTROL,
				"private, max-age=3600, immutable"
			)
			.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.body(file.resource());
	}

	@DeleteMapping("/resources/{resourceId}")
	@Operation(summary = "강의실 일반 자료 삭제")
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long resourceId
	) {
		resourceService.delete(user.userId(), user.role(), resourceId);
		return ApiResponse.success(null);
	}

	private MediaType downloadContentType(String fileName) {
		String extension = StringUtils.getFilenameExtension(fileName);
		if (extension == null) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
		return DOWNLOAD_MEDIA_TYPES.getOrDefault(
			extension.toLowerCase(Locale.ROOT),
			MediaType.APPLICATION_OCTET_STREAM
		);
	}
}
