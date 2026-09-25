package io.edupilot.usernote;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.QuizQuestionResult;
import io.edupilot.quiz.QuizQuestionResultService;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.usernote.dto.CreateWrongAnswerNoteRequest;
import io.edupilot.usernote.dto.PatchWrongAnswerNoteRequest;
import io.edupilot.usernote.dto.WrongAnswerNoteListResponse;
import io.edupilot.usernote.dto.WrongAnswerNoteResponse;

@Service
public class WrongAnswerNoteService {

	private static final int MAX_MEMO_BYTES = 65_535;

	private final WrongAnswerNoteRepository noteRepository;
	private final QuizQuestionResultService resultService;
	private final UserRepository userRepository;
	private final Clock clock;

	public WrongAnswerNoteService(
		WrongAnswerNoteRepository noteRepository,
		QuizQuestionResultService resultService,
		UserRepository userRepository,
		Clock clock
	) {
		this.noteRepository = noteRepository;
		this.resultService = resultService;
		this.userRepository = userRepository;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CreateResult<WrongAnswerNoteResponse> create(
		Long userId, CreateWrongAnswerNoteRequest request
	) {
		if (request == null) {
			throw invalid();
		}
		String clientId = validClientId(request.clientId());
		User user = userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		if (clientId != null) {
			var existing = noteRepository.findByUser_IdAndClientId(userId, clientId);
			if (existing.isPresent()) {
				return new CreateResult<>(WrongAnswerNoteResponse.from(existing.get()), false);
			}
		}
		QuizResultRef ref = QuizResultRef.parse(request.quizResultRef());
		var existingResult = noteRepository.findByUser_IdAndQuizResultRef(userId, ref.value());
		if (existingResult.isPresent()) {
			return new CreateResult<>(WrongAnswerNoteResponse.from(existingResult.get()), false);
		}
		QuizQuestionResult result = resultService.requireOwned(
			userId, ref.submissionId(), ref.questionId()
		);
		WrongAnswerQuestionSnapshot snapshot = new WrongAnswerQuestionSnapshot(
			result.questionId(), result.questionText(), result.quizType(),
			result.choices(), result.correctAnswer(), result.submittedAnswer()
		);
		WrongAnswerNote note = noteRepository.saveAndFlush(WrongAnswerNote.create(
			user, ref.value(), snapshot,
			validMemo(request.memo()), clientId, clock.instant()
		));
		return new CreateResult<>(WrongAnswerNoteResponse.from(note), true);
	}

	@Transactional(readOnly = true)
	public WrongAnswerNoteListResponse list(Long userId, int page, int size) {
		return WrongAnswerNoteListResponse.from(
			noteRepository.findByUser_IdAndDeletedAtIsNull(
				userId, PageRequest.of(page, size,
					Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")))
			)
		);
	}

	@Transactional
	public WrongAnswerNoteResponse update(
		Long userId, Long noteId, PatchWrongAnswerNoteRequest request
	) {
		WrongAnswerNote note = ownedNote(userId, noteId);
		if (request == null || !request.isMemoPresent()) {
			throw invalid();
		}
		note.updateMemo(validMemo(request.getMemo()), clock.instant());
		noteRepository.flush();
		return WrongAnswerNoteResponse.from(note);
	}

	@Transactional
	public void delete(Long userId, Long noteId) {
		ownedNote(userId, noteId).delete(clock.instant());
	}

	private WrongAnswerNote ownedNote(Long userId, Long noteId) {
		return noteRepository.findByIdAndUser_IdAndDeletedAtIsNull(noteId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.WRONG_ANSWER_NOTE_NOT_FOUND));
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

	private String validMemo(String memo) {
		if (memo != null && memo.getBytes(StandardCharsets.UTF_8).length > MAX_MEMO_BYTES) {
			throw invalid();
		}
		return memo;
	}

	private BusinessException invalid() {
		return new BusinessException(ErrorCode.VALIDATION_FAILED);
	}

	private record QuizResultRef(String value, Long submissionId, String questionId) {

		private static QuizResultRef parse(String value) {
			if (value == null || value.length() > 255) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
			int separator = value.indexOf(':');
			if (separator < 1 || separator == value.length() - 1) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
			try {
				long submissionId = Long.parseLong(value.substring(0, separator));
				if (submissionId <= 0) {
					throw new NumberFormatException("submissionId must be positive");
				}
				String questionId = value.substring(separator + 1);
				return new QuizResultRef(submissionId + ":" + questionId, submissionId, questionId);
			} catch (NumberFormatException exception) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
		}
	}
}
