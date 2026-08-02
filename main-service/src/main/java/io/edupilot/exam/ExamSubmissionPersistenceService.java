package io.edupilot.exam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomStatus;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.ExamSubmissionResponse;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.DeterministicAnswerGrader;
import io.edupilot.quiz.GradingVerdict;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@Service
public class ExamSubmissionPersistenceService {

	private final ClassroomService classroomService;
	private final ExamRepository examRepository;
	private final ExamQuestionRepository questionRepository;
	private final ExamSubmissionRepository submissionRepository;
	private final ExamAnswerRepository answerRepository;
	private final UserRepository userRepository;
	private final DeterministicAnswerGrader deterministicAnswerGrader;
	private final Clock clock;

	public ExamSubmissionPersistenceService(
		ClassroomService classroomService,
		ExamRepository examRepository,
		ExamQuestionRepository questionRepository,
		ExamSubmissionRepository submissionRepository,
		ExamAnswerRepository answerRepository,
		UserRepository userRepository,
		DeterministicAnswerGrader deterministicAnswerGrader,
		Clock clock
	) {
		this.classroomService = classroomService;
		this.examRepository = examRepository;
		this.questionRepository = questionRepository;
		this.submissionRepository = submissionRepository;
		this.answerRepository = answerRepository;
		this.userRepository = userRepository;
		this.deterministicAnswerGrader = deterministicAnswerGrader;
		this.clock = clock;
	}

	@Transactional
	public ExamSubmissionResponse create(
		Long userId,
		UserRole role,
		Long examId,
		SubmitExamRequest request
	) {
		requireLearner(role);
		Exam exam = examRepository.findByIdForUpdate(examId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXAM_NOT_FOUND));
		if (exam.getStatus() == ExamStatus.DRAFT) {
			throw new BusinessException(ErrorCode.EXAM_NOT_FOUND);
		}
		classroomService.requireVisible(userId, role, exam.getClassroomId());
		if (exam.getStatus() == ExamStatus.CLOSED) {
			throw new BusinessException(ErrorCode.EXAM_NOT_PUBLISHED);
		}
		if (exam.getClassroomStatus() == ClassroomStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.CLASSROOM_COMPLETED);
		}

		String requestId = normalizedRequestId(request);
		var duplicate = submissionRepository.findByExam_IdAndUser_IdAndRequestId(
			examId, userId, requestId
		);
		if (duplicate.isPresent()) {
			return response(duplicate.orElseThrow());
		}

		ExamSubmission latest = submissionRepository
			.findTopByExam_IdAndUser_IdOrderByAttemptNoDesc(examId, userId)
			.orElse(null);
		if (latest != null && !exam.isAllowRetake()) {
			throw new BusinessException(ErrorCode.EXAM_ALREADY_SUBMITTED);
		}
		int attemptNo = latest == null ? 1 : latest.getAttemptNo() + 1;
		List<ExamQuestion> questions = questionRepository
			.findByExam_IdOrderByQuestionNo(examId);
		if (questions.isEmpty() || exam.getTotalScore().signum() <= 0) {
			throw new BusinessException(ErrorCode.EXAM_NOT_PUBLISHED);
		}
		Map<String, String> submittedAnswers = validateAnswers(request, questions);
		User user = userRepository.findById(userId)
			.filter(User::isActive)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		ExamSubmission submission = submissionRepository.saveAndFlush(
			ExamSubmission.create(
				exam, user, attemptNo, requestId, exam.getTotalScore(), clock.instant()
			)
		);

		List<ExamAnswer> answers = new ArrayList<>();
		boolean hasAnsweredSubjective = false;
		for (ExamQuestion question : questions) {
			String questionId = questionId(question);
			String submittedAnswer = submittedAnswers.get(questionId);
			ExamAnswer answer = ExamAnswer.create(
				submission, question, submittedAnswer, question.getPoints()
			);
			if (submittedAnswer == null) {
				answer.recordGrade(BigDecimal.ZERO, Verdict.WRONG, null);
			} else if (question.getQuestionType() == ExamQuestionType.MCQ
				|| question.getQuestionType() == ExamQuestionType.OX) {
				var grade = deterministicAnswerGrader.grade(
					question.getPrivateAnswer().correctAnswer(),
					submittedAnswer,
					question.getPoints()
				);
				answer.recordGrade(
					grade.score(), toExamVerdict(grade.verdict()), null
				);
			} else {
				hasAnsweredSubjective = true;
			}
			answers.add(answer);
		}
		answerRepository.saveAll(answers);
		answerRepository.flush();
		if (!hasAnsweredSubjective) {
			BigDecimal score = answers.stream()
				.map(ExamAnswer::getScore)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			submission.complete(score, normalized(score, submission.getMaxScore()), clock.instant());
			submissionRepository.flush();
		}
		return ExamSubmissionResponse.from(submission, answers);
	}

	private Map<String, String> validateAnswers(
		SubmitExamRequest request,
		List<ExamQuestion> questions
	) {
		if (request == null || request.answers() == null) {
			throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
		}
		Map<String, ExamQuestion> questionsById = new HashMap<>();
		for (ExamQuestion question : questions) {
			questionsById.put(questionId(question), question);
		}
		Map<String, String> answers = new HashMap<>();
		Set<String> seen = new HashSet<>();
		for (ExamAnswerRequest answerRequest : request.answers()) {
			if (answerRequest == null || answerRequest.questionId() == null) {
				throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
			}
			String questionId = answerRequest.questionId().trim();
			ExamQuestion question = questionsById.get(questionId);
			if (question == null || !seen.add(questionId)) {
				throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
			}
			String answer = answerRequest.answer() == null
				? null : answerRequest.answer().trim();
			if (answer == null || answer.isEmpty()) {
				throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
			}
			validateAnswerType(question, answer);
			answers.put(questionId, answer);
		}
		return Map.copyOf(answers);
	}

	private void validateAnswerType(ExamQuestion question, String answer) {
		switch (question.getQuestionType()) {
			case MCQ -> {
				if (question.getPublicQuestion().options() == null
					|| question.getPublicQuestion().options().stream()
						.noneMatch(option -> option.choiceId().equals(answer))) {
					throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
				}
			}
			case OX -> {
				if (!answer.equals("true") && !answer.equals("false")) {
					throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
				}
			}
			case SHORT, ESSAY -> {
				// Non-blank text was already checked above.
			}
		}
	}

	private String normalizedRequestId(SubmitExamRequest request) {
		String requestId = request == null || request.requestId() == null
			? "" : request.requestId().trim();
		if (requestId.isEmpty() || requestId.length() > 255) {
			throw new BusinessException(ErrorCode.INVALID_EXAM_ANSWER);
		}
		return requestId;
	}

	private ExamSubmissionResponse response(ExamSubmission submission) {
		return ExamSubmissionResponse.from(
			submission,
			answerRepository.findBySubmission_IdOrderByQuestion_Id(submission.getId())
		);
	}

	private BigDecimal normalized(BigDecimal score, BigDecimal maxScore) {
		if (maxScore == null || maxScore.signum() <= 0) {
			throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		return score.multiply(BigDecimal.valueOf(100))
			.divide(maxScore, 2, RoundingMode.HALF_UP);
	}

	private String questionId(ExamQuestion question) {
		return "q" + question.getQuestionNo();
	}

	private Verdict toExamVerdict(GradingVerdict verdict) {
		return Verdict.valueOf(verdict.name());
	}

	private void requireLearner(UserRole role) {
		if (role != UserRole.LEARNER) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
	}
}
