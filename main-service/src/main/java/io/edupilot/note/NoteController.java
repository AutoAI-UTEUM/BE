package io.edupilot.note;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.note.dto.CreateNoteRequest;
import io.edupilot.note.dto.NoteListResponse;
import io.edupilot.note.dto.NoteResponse;
import io.edupilot.note.dto.UpdateNoteRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Validated
@Tag(name = "Notes")
@SecurityRequirement(name = "bearerAuth")
public class NoteController {

	private final NoteService noteService;

	public NoteController(NoteService noteService) {
		this.noteService = noteService;
	}

	@PostMapping("/api/sessions/{sessionId}/notes")
	@Operation(summary = "학습 노트 생성")
	public ApiResponse<NoteResponse> create(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId,
		@Valid @RequestBody CreateNoteRequest request
	) {
		return ApiResponse.success(noteService.create(
			authenticatedUser.userId(),
			sessionId,
			request
		));
	}

	@GetMapping("/api/materials/{materialId}/notes")
	@Operation(summary = "자료별 학습 노트 목록 조회")
	public ApiResponse<NoteListResponse> listByMaterial(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long materialId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(noteService.listByMaterial(
			authenticatedUser.userId(),
			materialId,
			page,
			size
		));
	}

	@GetMapping("/api/sessions/{sessionId}/notes")
	@Operation(summary = "세션 자료의 학습 노트 목록 조회")
	public ApiResponse<NoteListResponse> listBySession(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(noteService.listBySession(
			authenticatedUser.userId(),
			sessionId,
			page,
			size
		));
	}

	@PatchMapping("/api/notes/{noteId}")
	@Operation(summary = "학습 노트 수정")
	public ApiResponse<NoteResponse> update(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long noteId,
		@Valid @RequestBody UpdateNoteRequest request
	) {
		return ApiResponse.success(noteService.update(
			authenticatedUser.userId(),
			noteId,
			request
		));
	}

	@DeleteMapping("/api/notes/{noteId}")
	@Operation(summary = "학습 노트 삭제")
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long noteId
	) {
		noteService.delete(authenticatedUser.userId(), noteId);
		return ApiResponse.success(null);
	}
}
