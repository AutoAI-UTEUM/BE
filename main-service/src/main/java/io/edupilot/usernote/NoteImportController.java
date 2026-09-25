package io.edupilot.usernote;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.usernote.dto.ImportNotesRequest;
import io.edupilot.usernote.dto.ImportNotesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "User Notes")
@SecurityRequirement(name = "bearerAuth")
public class NoteImportController {

	private final NoteImportService importService;

	public NoteImportController(NoteImportService importService) {
		this.importService = importService;
	}

	@PostMapping("/api/user-notes/import")
	@Operation(summary = "로컬 수동·오답 노트 1회 이관")
	public ApiResponse<ImportNotesResponse> importNotes(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestBody ImportNotesRequest request
	) {
		return ApiResponse.success(importService.importOnce(user.userId(), request));
	}
}
