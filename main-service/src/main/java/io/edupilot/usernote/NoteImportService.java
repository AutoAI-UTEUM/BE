package io.edupilot.usernote;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.usernote.dto.CreateUserNoteRequest;
import io.edupilot.usernote.dto.CreateWrongAnswerNoteRequest;
import io.edupilot.usernote.dto.ImportNotesRequest;
import io.edupilot.usernote.dto.ImportNotesResponse;

@Service
public class NoteImportService {

	private static final int MAX_ITEMS_PER_ARRAY = 200;

	private final UserNoteService userNoteService;
	private final WrongAnswerNoteService wrongAnswerNoteService;
	private final NoteImportRateLimiter rateLimiter;

	public NoteImportService(
		UserNoteService userNoteService,
		WrongAnswerNoteService wrongAnswerNoteService,
		NoteImportRateLimiter rateLimiter
	) {
		this.userNoteService = userNoteService;
		this.wrongAnswerNoteService = wrongAnswerNoteService;
		this.rateLimiter = rateLimiter;
	}

	public ImportNotesResponse importOnce(Long userId, ImportNotesRequest request) {
		if (request == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		List<ImportNotesRequest.UserNoteItem> notes = request.notes() == null
			? List.of() : request.notes();
		List<ImportNotesRequest.WrongAnswerItem> wrongAnswers = request.wrongAnswers() == null
			? List.of() : request.wrongAnswers();
		if (notes.size() > MAX_ITEMS_PER_ARRAY || wrongAnswers.size() > MAX_ITEMS_PER_ARRAY) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (!rateLimiter.allow(userId)) {
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}

		int imported = 0;
		int skipped = 0;
		List<ImportNotesResponse.FailedItem> failed = new ArrayList<>();
		for (ImportNotesRequest.UserNoteItem item : notes) {
			try {
				requireClientId(item == null ? null : item.clientId());
				Instant createdAt = parseCreatedAt(item.createdAt());
				var result = userNoteService.create(userId, new CreateUserNoteRequest(
					item.materialId(), item.pageNumber(), item.title(),
					item.content(), item.clientId()
				), createdAt);
				if (result.created()) { imported++; } else { skipped++; }
			} catch (BusinessException exception) {
				failed.add(new ImportNotesResponse.FailedItem(
					item == null ? null : item.clientId(), exception.errorCode().code()
				));
			}
		}
		for (ImportNotesRequest.WrongAnswerItem item : wrongAnswers) {
			try {
				requireClientId(item == null ? null : item.clientId());
				var result = wrongAnswerNoteService.create(
					userId, new CreateWrongAnswerNoteRequest(
						item.quizResultRef(), item.memo(), item.clientId()
					)
				);
				if (result.created()) { imported++; } else { skipped++; }
			} catch (BusinessException exception) {
				failed.add(new ImportNotesResponse.FailedItem(
					item == null ? null : item.clientId(), exception.errorCode().code()
				));
			}
		}
		return new ImportNotesResponse(imported, skipped, List.copyOf(failed));
	}

	private void requireClientId(String clientId) {
		if (clientId == null || clientId.isBlank() || clientId.length() > 64) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private Instant parseCreatedAt(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException exception) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}
}
