package io.edupilot.usernote;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.usernote.dto.CreateUserNoteRequest;
import io.edupilot.usernote.dto.PatchUserNoteRequest;
import io.edupilot.usernote.dto.UserNoteListResponse;
import io.edupilot.usernote.dto.UserNoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Validated
@RequestMapping("/api/user-notes")
@Tag(name = "User Notes")
@SecurityRequirement(name = "bearerAuth")
public class UserNoteController {

	private final UserNoteService noteService;

	public UserNoteController(UserNoteService noteService) {
		this.noteService = noteService;
	}

	@GetMapping
	@Operation(summary = "내 수동 노트 목록")
	public ApiResponse<UserNoteListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(required = false) Long materialId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(noteService.list(user.userId(), materialId, page, size));
	}

	@GetMapping("/{noteId}")
	@Operation(summary = "내 수동 노트 상세")
	public ApiResponse<UserNoteResponse> detail(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long noteId
	) {
		return ApiResponse.success(noteService.detail(user.userId(), noteId));
	}

	@PostMapping
	@Operation(summary = "수동 노트 생성")
	public ResponseEntity<ApiResponse<UserNoteResponse>> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestBody CreateUserNoteRequest request
	) {
		var result = noteService.create(user.userId(), request, null);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(ApiResponse.success(result.data()));
	}

	@PatchMapping("/{noteId}")
	@Operation(summary = "내 수동 노트 수정")
	public ApiResponse<UserNoteResponse> update(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long noteId,
		@RequestBody PatchUserNoteRequest request
	) {
		return ApiResponse.success(noteService.update(user.userId(), noteId, request));
	}

	@DeleteMapping("/{noteId}")
	@Operation(summary = "내 수동 노트 소프트 삭제")
	public ResponseEntity<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long noteId
	) {
		noteService.delete(user.userId(), noteId);
		return ResponseEntity.noContent().build();
	}
}
