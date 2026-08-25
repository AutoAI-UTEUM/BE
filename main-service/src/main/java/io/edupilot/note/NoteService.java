package io.edupilot.note;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.material.MaterialStatus;
import io.edupilot.note.dto.CreateNoteRequest;
import io.edupilot.note.dto.NoteListResponse;
import io.edupilot.note.dto.NoteResponse;
import io.edupilot.note.dto.UpdateNoteRequest;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
public class NoteService {

	private static final int CONTENT_MAX_LENGTH = 10000;

	private final NoteRepository noteRepository;
	private final LearningSessionRepository sessionRepository;
	private final MaterialAccessService materialAccessService;
	private final ChatMessageRepository messageRepository;
	private final UserRepository userRepository;

	public NoteService(
		NoteRepository noteRepository,
		LearningSessionRepository sessionRepository,
		MaterialAccessService materialAccessService,
		ChatMessageRepository messageRepository,
		UserRepository userRepository
	) {
		this.noteRepository = noteRepository;
		this.sessionRepository = sessionRepository;
		this.materialAccessService = materialAccessService;
		this.messageRepository = messageRepository;
		this.userRepository = userRepository;
	}

	@Transactional
	public NoteResponse create(
		Long userId,
		Long sessionId,
		CreateNoteRequest request
	) {
		String content = validateContent(request.content());
		LearningSession session = visibleOwnedSession(userId, sessionId);
		LearningMaterial material = materialAccessService.requireAccessible(
			userId,
			session.getMaterialId()
		);
		validatePageNumber(request.pageNumber(), material.getPageCount());
		ChatMessage sourceMessage = sourceMessage(
			sessionId,
			request.sourceMessageId()
		);
		User user = userRepository.getReferenceById(userId);
		Note note = noteRepository.saveAndFlush(Note.create(
			user,
			material,
			session,
			request.pageNumber(),
			sourceMessage,
			content
		));
		return NoteResponse.from(note);
	}

	@Transactional(readOnly = true)
	public NoteListResponse listByMaterial(
		Long userId,
		Long materialId,
		int page,
		int size
	) {
		materialAccessService.requireAccessible(userId, materialId);
		PageRequest pageable = PageRequest.of(
			page,
			size,
			Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
		);
		Page<Note> notes = noteRepository
			.findByUser_IdAndMaterial_IdAndMaterial_Status(
				userId,
				materialId,
				MaterialStatus.ACTIVE,
				pageable
			);
		return NoteListResponse.from(notes);
	}

	@Transactional(readOnly = true)
	public NoteListResponse listBySession(
		Long userId,
		Long sessionId,
		int page,
		int size
	) {
		LearningSession session = visibleOwnedSession(userId, sessionId);
		return listByMaterial(userId, session.getMaterialId(), page, size);
	}

	@Transactional
	public NoteResponse update(
		Long userId,
		Long noteId,
		UpdateNoteRequest request
	) {
		Note note = ownedNote(userId, noteId);
		note.updateContent(validateContent(request.content()));
		noteRepository.flush();
		return NoteResponse.from(note);
	}

	@Transactional
	public void delete(Long userId, Long noteId) {
		Note note = ownedNote(userId, noteId);
		noteRepository.delete(note);
	}

	private LearningSession visibleOwnedSession(Long userId, Long sessionId) {
		return sessionRepository.findByIdAndUser_Id(sessionId, userId)
			.filter(session -> session.getStatus() != SessionStatus.DELETED)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
	}

	private ChatMessage sourceMessage(Long sessionId, Long sourceMessageId) {
		if (sourceMessageId == null) {
			return null;
		}
		return messageRepository.findByIdAndSession_Id(sourceMessageId, sessionId)
			.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
	}

	private Note ownedNote(Long userId, Long noteId) {
		return noteRepository.findByIdAndUser_Id(noteId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOTE_NOT_FOUND));
	}

	private String validateContent(String content) {
		if (content == null || content.isBlank() || content.length() > CONTENT_MAX_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return content;
	}

	private void validatePageNumber(Integer pageNumber, Integer pageCount) {
		if (pageNumber == null) {
			return;
		}
		if (pageNumber < 1 || pageCount == null || pageNumber > pageCount) {
			throw new BusinessException(ErrorCode.PAGE_OUT_OF_RANGE);
		}
	}
}
