package io.edupilot.usernote;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.usernote.dto.CreateUserNoteRequest;
import io.edupilot.usernote.dto.PatchUserNoteRequest;
import io.edupilot.usernote.dto.UserNoteListResponse;
import io.edupilot.usernote.dto.UserNoteResponse;

@Service
public class UserNoteService {

	private static final int MAX_CONTENT_BYTES = 1024 * 1024;
	private static final int MAX_ACTIVE_NOTES = 2_000;

	private final UserNoteRepository noteRepository;
	private final UserRepository userRepository;
	private final MaterialAccessService materialAccessService;
	private final Clock clock;

	public UserNoteService(
		UserNoteRepository noteRepository,
		UserRepository userRepository,
		MaterialAccessService materialAccessService,
		Clock clock
	) {
		this.noteRepository = noteRepository;
		this.userRepository = userRepository;
		this.materialAccessService = materialAccessService;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CreateResult<UserNoteResponse> create(
		Long userId, CreateUserNoteRequest request, Instant importedCreatedAt
	) {
		if (request == null) {
			throw invalid();
		}
		String clientId = validClientId(request.clientId());
		// Serialize this user's creates so clientId retries and the active-note cap stay exact.
		User user = userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		if (clientId != null) {
			var existing = noteRepository.findByUser_IdAndClientId(userId, clientId);
			if (existing.isPresent()) {
				return new CreateResult<>(UserNoteResponse.from(existing.get()), false);
			}
		}
		String title = validTitle(request.title());
		String content = validContent(request.content());
		LearningMaterial material = request.materialId() == null ? null
			: materialAccessService.requireAccessible(userId, request.materialId());
		validPage(request.pageNumber(), material);
		if (noteRepository.countByUser_IdAndDeletedAtIsNull(userId) >= MAX_ACTIVE_NOTES) {
			throw new BusinessException(ErrorCode.NOTE_LIMIT_EXCEEDED);
		}
		Instant now = clock.instant();
		UserNote note = noteRepository.saveAndFlush(UserNote.create(
			user, material, request.pageNumber(),
			title, content, clientId,
			importedCreatedAt == null ? now : importedCreatedAt, now
		));
		return new CreateResult<>(UserNoteResponse.from(note), true);
	}

	@Transactional(readOnly = true)
	public UserNoteListResponse list(Long userId, Long materialId, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size,
			Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
		Page<UserNote> notes = materialId == null
			? noteRepository.findByUser_IdAndDeletedAtIsNull(userId, pageable)
			: noteRepository.findByUser_IdAndMaterial_IdAndDeletedAtIsNull(
				userId, materialId, pageable);
		return UserNoteListResponse.from(notes);
	}

	@Transactional(readOnly = true)
	public UserNoteResponse detail(Long userId, Long noteId) {
		return UserNoteResponse.from(ownedNote(userId, noteId));
	}

	@Transactional
	public UserNoteResponse update(Long userId, Long noteId, PatchUserNoteRequest request) {
		UserNote note = ownedNote(userId, noteId);
		if (request == null || !request.hasAnyField()) {
			throw invalid();
		}
		String title = request.isTitlePresent() ? validTitle(request.getTitle()) : null;
		String content = request.isContentPresent() ? validContent(request.getContent()) : null;
		if (request.isPageNumberPresent()) {
			LearningMaterial material = note.getMaterialId() == null ? null
				: materialAccessService.requireAccessible(userId, note.getMaterialId());
			validPage(request.getPageNumber(), material);
		}
		note.update(
			title, request.isTitlePresent(), content, request.isContentPresent(),
			request.getPageNumber(), request.isPageNumberPresent(), clock.instant()
		);
		noteRepository.flush();
		return UserNoteResponse.from(note);
	}

	@Transactional
	public void delete(Long userId, Long noteId) {
		UserNote note = ownedNote(userId, noteId);
		note.delete(clock.instant());
	}

	private UserNote ownedNote(Long userId, Long noteId) {
		return noteRepository.findByIdAndUser_IdAndDeletedAtIsNull(noteId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOTE_NOT_FOUND));
	}

	private String validTitle(String title) {
		if (title == null || title.isBlank() || title.length() > 200) {
			throw invalid();
		}
		return title.trim();
	}

	private String validContent(String content) {
		if (content == null) {
			throw invalid();
		}
		if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
			throw new BusinessException(ErrorCode.NOTE_TOO_LARGE);
		}
		return content;
	}

	private String validClientId(String clientId) {
		if (clientId == null) {
			return null;
		}
		if (clientId.isBlank() || clientId.length() > 64) {
			throw invalid();
		}
		return clientId;
	}

	private void validPage(Integer pageNumber, LearningMaterial material) {
		if (pageNumber == null) {
			return;
		}
		if (pageNumber < 1 || material != null
			&& (material.getPageCount() == null || pageNumber > material.getPageCount())) {
			throw new BusinessException(ErrorCode.PAGE_OUT_OF_RANGE);
		}
	}

	private BusinessException invalid() {
		return new BusinessException(ErrorCode.VALIDATION_FAILED);
	}
}
