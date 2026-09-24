package io.edupilot.exam;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.ExamAttemptDraftResponse;
import io.edupilot.exam.dto.ExamAttemptDraftSaveResponse;
import io.edupilot.exam.dto.SaveExamAttemptDraftRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@Service
public class ExamAttemptDraftService {

	private static final int MAX_DRAFT_BYTES = 256 * 1024;

	private final StudentExamService studentExamService;
	private final ExamAttemptDraftRepository draftRepository;
	private final ExamQuestionRepository questionRepository;
	private final ExamSubmissionRepository submissionRepository;
	private final UserRepository userRepository;
	private final ExamDraftRateLimiter rateLimiter;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public ExamAttemptDraftService(
		StudentExamService studentExamService,
		ExamAttemptDraftRepository draftRepository,
		ExamQuestionRepository questionRepository,
		ExamSubmissionRepository submissionRepository,
		UserRepository userRepository,
		ExamDraftRateLimiter rateLimiter,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.studentExamService = studentExamService;
		this.draftRepository = draftRepository;
		this.questionRepository = questionRepository;
		this.submissionRepository = submissionRepository;
		this.userRepository = userRepository;
		this.rateLimiter = rateLimiter;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public ExamAttemptDraftSaveResponse save(
		Long userId,
		UserRole role,
		Long examId,
		SaveExamAttemptDraftRequest request
	) {
		Exam exam = studentExamService.requirePublishedExam(userId, role, examId);
		if (submissionRepository.existsByExam_IdAndUser_Id(examId, userId)) {
			throw new BusinessException(ErrorCode.EXAM_ALREADY_SUBMITTED);
		}
		if (!rateLimiter.allow(userId, examId)) {
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}
		if (request == null || request.answers() == null
			|| request.version() != null && request.version() < 0) {
			throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
		}
		if (objectMapper.valueToTree(request).toString()
			.getBytes(StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES) {
			throw new BusinessException(ErrorCode.DRAFT_TOO_LARGE);
		}
		List<ExamAnswerRequest> answers = validatedAnswers(examId, request.answers());
		Instant now = clock.instant();
		Integer version = request.version();
		if (version == null || version == 0) {
			return create(exam, userId, answers, now);
		}
		if (draftRepository.updateIfVersionMatches(
			examId, userId, version, answers, now
		) != 1) {
			throw conflict(examId, userId);
		}
		return new ExamAttemptDraftSaveResponse(version + 1, now);
	}

	@Transactional(readOnly = true)
	public ExamAttemptDraftResponse get(Long userId, UserRole role, Long examId) {
		studentExamService.requirePublishedExam(userId, role, examId);
		return draftRepository.findByExam_IdAndUser_Id(examId, userId)
			.map(ExamAttemptDraftResponse::from)
			.orElse(null);
	}

	private ExamAttemptDraftSaveResponse create(
		Exam exam,
		Long userId,
		List<ExamAnswerRequest> answers,
		Instant now
	) {
		User user = userRepository.findById(userId)
			.filter(User::isActive)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		try {
			ExamAttemptDraft saved = draftRepository.saveAndFlush(
				ExamAttemptDraft.create(exam, user, answers, now)
			);
			return new ExamAttemptDraftSaveResponse(saved.getVersion(), saved.getUpdatedAt());
		} catch (DataIntegrityViolationException exception) {
			if (draftRepository.findByExam_IdAndUser_Id(exam.getId(), userId).isPresent()) {
				throw conflict(exam.getId(), userId);
			}
			throw exception;
		}
	}

	private List<ExamAnswerRequest> validatedAnswers(
		Long examId,
		List<ExamAnswerRequest> requested
	) {
		Set<String> validIds = new HashSet<>();
		for (ExamQuestion question : questionRepository.findByExam_IdOrderByQuestionNo(examId)) {
			validIds.add("q" + question.getQuestionNo());
		}
		Set<String> seen = new HashSet<>();
		List<ExamAnswerRequest> answers = new ArrayList<>();
		for (ExamAnswerRequest item : requested) {
			if (item == null || item.questionId() == null) {
				throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
			}
			String questionId = item.questionId().trim();
			if (!validIds.contains(questionId) || !seen.add(questionId)) {
				throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
			}
			answers.add(new ExamAnswerRequest(questionId, item.answer()));
		}
		return List.copyOf(answers);
	}

	private ExamDraftVersionConflictException conflict(Long examId, Long userId) {
		return new ExamDraftVersionConflictException(
			draftRepository.findByExam_IdAndUser_Id(examId, userId)
				.map(ExamAttemptDraftResponse::from)
				.orElse(null)
		);
	}
}
