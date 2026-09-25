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
import io.edupilot.usernote.dto.CreateWrongAnswerNoteRequest;
import io.edupilot.usernote.dto.PatchWrongAnswerNoteRequest;
import io.edupilot.usernote.dto.WrongAnswerNoteListResponse;
import io.edupilot.usernote.dto.WrongAnswerNoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Validated
@RequestMapping("/api/wrong-answer-notes")
@Tag(name = "Wrong Answer Notes")
@SecurityRequirement(name = "bearerAuth")
public class WrongAnswerNoteController {

	private final WrongAnswerNoteService noteService;

	public WrongAnswerNoteController(WrongAnswerNoteService noteService) {
		this.noteService = noteService;
	}

	@GetMapping
	@Operation(summary = "내 오답 노트 목록")
	public ApiResponse<WrongAnswerNoteListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(noteService.list(user.userId(), page, size));
	}

	@PostMapping
	@Operation(summary = "퀴즈 제출 문항에서 오답 노트 생성")
	public ResponseEntity<ApiResponse<WrongAnswerNoteResponse>> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestBody CreateWrongAnswerNoteRequest request
	) {
		var result = noteService.create(user.userId(), request);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(ApiResponse.success(result.data()));
	}

	@PatchMapping("/{noteId}")
	@Operation(summary = "내 오답 노트 메모 수정")
	public ApiResponse<WrongAnswerNoteResponse> update(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long noteId,
		@RequestBody PatchWrongAnswerNoteRequest request
	) {
		return ApiResponse.success(noteService.update(user.userId(), noteId, request));
	}

	@DeleteMapping("/{noteId}")
	@Operation(summary = "내 오답 노트 소프트 삭제")
	public ResponseEntity<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long noteId
	) {
		noteService.delete(user.userId(), noteId);
		return ResponseEntity.noContent().build();
	}
}
