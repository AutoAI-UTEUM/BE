package io.edupilot.exam;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomStatus;
import io.edupilot.exam.dto.CreateExamRequest;
import io.edupilot.exam.dto.ExamQuestionRequest;
import io.edupilot.exam.dto.ExamSubmissionResponse;
import io.edupilot.exam.dto.InstructorExamDetailResponse;
import io.edupilot.exam.dto.InstructorExamListItemResponse;
import io.edupilot.exam.dto.InstructorExamListResponse;
import io.edupilot.exam.dto.InstructorSubmissionListItemResponse;
import io.edupilot.exam.dto.InstructorSubmissionListResponse;
import io.edupilot.exam.dto.UpdateExamRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserRole;

@Service
public class InstructorExamService {

	private static final String SCHEMA_VERSION = "1.0";

	private final ClassroomService classroomService;
	private final ExamRepository examRepository;
	private final ExamQuestionRepository questionRepository;
	private final ExamSubmissionRepository submissionRepository;
	private final ExamAnswerRepository answerRepository;
	private final Clock clock;

	public InstructorExamService(
		ClassroomService classroomService,
		ExamRepository examRepository,
		ExamQuestionRepository questionRepository,
		ExamSubmissionRepository submissionRepository,
		ExamAnswerRepository answerRepository,
		Clock clock
	) {
		this.classroomService = classroomService;
		this.examRepository = examRepository;
		this.questionRepository = questionRepository;
		this.submissionRepository = submissionRepository;
		this.answerRepository = answerRepository;
		this.clock = clock;
	}

	@Transactional
	public InstructorExamDetailResponse create(
		Long userId,
		UserRole role,
		Long classroomId,
		CreateExamRequest request
	) {
		Classroom classroom = classroomService.requireOwnerForUpdate(userId, role, classroomId);
		classroomService.assertWritable(classroom);
		validateWeekNumber(classroom, request.weekNumber());
		Exam exam = examRepository.saveAndFlush(Exam.create(
			classroom,
			request.weekNumber(),
			normalizedRequired(request.title(), 200),
			normalizedOptional(request.description(), 500),
			Boolean.TRUE.equals(request.allowRetake())
		));
		List<ExamQuestion> questions = replaceQuestions(exam, request.questions());
		return InstructorExamDetailResponse.from(exam, questions);
	}

	@Transactional(readOnly = true)
	public InstructorExamListResponse list(
		Long userId,
		UserRole role,
		Long classroomId,
		ExamStatus status,
		int page,
		int size
	) {
		classroomService.requireOwner(userId, role, classroomId);
		PageRequest pageable = PageRequest.of(
			page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
		);
		Page<Exam> exams = status == null
			? examRepository.findByClassroom_Id(classroomId, pageable)
			: examRepository.findByClassroom_IdAndStatus(classroomId, status, pageable);
		return new InstructorExamListResponse(
			exams.getContent().stream()
				.map(exam -> InstructorExamListItemResponse.from(
					exam,
					submissionRepository.countDistinctUsersByExamId(exam.getId())
				))
				.toList(),
			exams.getNumber(), exams.getSize(), exams.getTotalElements(), exams.getTotalPages()
		);
	}

	@Transactional(readOnly = true)
	public InstructorExamDetailResponse detail(
		Long userId,
		UserRole role,
		Long examId
	) {
		Exam exam = requireOwnedExam(userId, role, examId);
		return detailResponse(exam);
	}

	@Transactional
	public InstructorExamDetailResponse update(
		Long userId,
		UserRole role,
		Long examId,
		UpdateExamRequest request
	) {
		Exam exam = requireOwnedExamForUpdate(userId, role, examId);
		assertActiveClassroom(exam);
		assertDraft(exam);
		if (request == null || !request.hasAnyField()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (request.isTitlePresent() && request.getTitle() == null
			|| request.isAllowRetakePresent() && request.getAllowRetake() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (request.isWeekNumberPresent()) {
			validateWeekNumber(exam.getClassroomWeekCount(), request.getWeekNumber());
		}
		exam.update(
			request.isTitlePresent() ? normalizedRequired(request.getTitle(), 200) : null,
			request.isDescriptionPresent(),
			request.isDescriptionPresent()
				? normalizedOptional(request.getDescription(), 500) : null,
			request.isWeekNumberPresent(),
			request.getWeekNumber(),
			request.isAllowRetakePresent() ? request.getAllowRetake() : null
		);
		List<ExamQuestion> questions;
		if (request.isQuestionsPresent()) {
			questionRepository.deleteByExam_Id(examId);
			questionRepository.flush();
			questions = replaceQuestions(exam, request.getQuestions());
		} else {
			questions = questionRepository.findByExam_IdOrderByQuestionNo(examId);
		}
		examRepository.flush();
		return InstructorExamDetailResponse.from(exam, questions);
	}

	@Transactional
	public InstructorExamDetailResponse publish(Long userId, UserRole role, Long examId) {
		Exam exam = requireOwnedExamForUpdate(userId, role, examId);
		assertActiveClassroom(exam);
		if (exam.getStatus() == ExamStatus.PUBLISHED) {
			return detailResponse(exam);
		}
		assertDraft(exam);
		List<ExamQuestion> questions = questionRepository.findByExam_IdOrderByQuestionNo(examId);
		validatePublish(exam, questions);
		exam.publish(clock.instant());
		examRepository.flush();
		return InstructorExamDetailResponse.from(exam, questions);
	}

	@Transactional
	public InstructorExamDetailResponse close(Long userId, UserRole role, Long examId) {
		Exam exam = requireOwnedExamForUpdate(userId, role, examId);
		if (exam.getStatus() == ExamStatus.CLOSED) {
			return detailResponse(exam);
		}
		if (exam.getStatus() != ExamStatus.PUBLISHED) {
			throw new BusinessException(ErrorCode.EXAM_NOT_PUBLISHED);
		}
		exam.close(clock.instant());
		examRepository.flush();
		return detailResponse(exam);
	}

	@Transactional
	public void delete(Long userId, UserRole role, Long examId) {
		Exam exam = requireOwnedExamForUpdate(userId, role, examId);
		assertDraft(exam);
		questionRepository.deleteByExam_Id(examId);
		questionRepository.flush();
		examRepository.delete(exam);
	}

	@Transactional(readOnly = true)
	public InstructorSubmissionListResponse submissions(
		Long userId,
		UserRole role,
		Long examId,
		int page,
		int size
	) {
		Exam exam = requireOwnedExam(userId, role, examId);
		PageRequest pageable = PageRequest.of(
			page, size, Sort.by(Sort.Order.desc("attemptNo"), Sort.Order.desc("id"))
		);
		Page<ExamSubmission> submissions = submissionRepository.findLatestByExamId(
			exam.getId(), pageable
		);
		return new InstructorSubmissionListResponse(
			submissions.getContent().stream()
				.map(submission -> InstructorSubmissionListItemResponse.from(
					submission,
					submissionRepository.countByExam_IdAndUser_Id(
						examId, submission.getUserId()
					)
				))
				.toList(),
			submissions.getNumber(), submissions.getSize(),
			submissions.getTotalElements(), submissions.getTotalPages()
		);
	}

	@Transactional(readOnly = true)
	public ExamSubmissionResponse submissionDetail(
		Long userId,
		UserRole role,
		Long examId,
		Long submissionId
	) {
		requireOwnedExam(userId, role, examId);
		ExamSubmission submission = submissionRepository.findByIdAndExam_Id(
			submissionId, examId
		).orElseThrow(() -> new BusinessException(ErrorCode.EXAM_NOT_FOUND));
		return ExamSubmissionResponse.from(
			submission,
			answerRepository.findBySubmission_IdOrderByQuestion_Id(submissionId)
		);
	}

	private List<ExamQuestion> replaceQuestions(
		Exam exam,
		List<ExamQuestionRequest> requests
	) {
		List<ExamQuestionRequest> safeRequests = requests == null ? List.of() : requests;
		List<ExamQuestion> questions = new ArrayList<>();
		BigDecimal totalScore = BigDecimal.ZERO;
		for (int index = 0; index < safeRequests.size(); index++) {
			ExamQuestionRequest request = safeRequests.get(index);
			validateDraftQuestion(request);
			ExamQuestion question = ExamQuestion.create(
				exam,
				index + 1,
				request.questionType(),
				request.points(),
				new ExamPublicQuestion(
					normalizedRequired(request.questionText(), 10_000),
					request.options() == null ? List.of() : request.options().stream()
						.map(option -> new io.edupilot.quiz.QuizOption(
							normalizedRequired(option.optionId(), 100),
							normalizedRequired(option.text(), 1_000)
						))
						.toList()
				),
				new ExamPrivateAnswer(
					normalizedOptional(request.answerChoiceId(), 100),
					request.answerValue(),
					normalizedOptional(request.explanation(), 10_000),
					normalizedOptional(request.referenceAnswer(), 10_000),
					normalizedOptional(request.modelAnswer(), 10_000),
					request.rubric() == null ? List.of() : List.copyOf(request.rubric())
				),
				SCHEMA_VERSION
			);
			questions.add(question);
			totalScore = totalScore.add(request.points());
		}
		if (!questions.isEmpty()) {
			questionRepository.saveAll(questions);
			questionRepository.flush();
		}
		exam.replaceTotalScore(totalScore);
		examRepository.flush();
		return List.copyOf(questions);
	}

	private void validateDraftQuestion(ExamQuestionRequest request) {
		if (request == null || request.questionType() == null || request.points() == null
			|| request.points().signum() <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		normalizedRequired(request.questionText(), 10_000);
		if (request.rubric() != null) {
			request.rubric().forEach(criterion -> {
				if (criterion == null || criterion.weight() == null
					|| criterion.weight().signum() <= 0
					|| normalizedRequired(criterion.criterion(), 500).isEmpty()) {
					throw new BusinessException(ErrorCode.VALIDATION_FAILED);
				}
			});
		}
	}

	private void validatePublish(Exam exam, List<ExamQuestion> questions) {
		if (questions.isEmpty() || exam.getTotalScore().signum() <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		for (ExamQuestion question : questions) {
			var privateAnswer = question.getPrivateAnswer();
			switch (question.getQuestionType()) {
				case MCQ -> {
					String answer = required(privateAnswer.answerChoiceId());
					if (question.getPublicQuestion().options() == null
						|| question.getPublicQuestion().options().isEmpty()
						|| question.getPublicQuestion().options().stream()
							.noneMatch(option -> answer.equals(option.choiceId()))) {
						throw new BusinessException(ErrorCode.VALIDATION_FAILED);
					}
				}
				case OX -> {
					if (privateAnswer.answerValue() == null) {
						throw new BusinessException(ErrorCode.VALIDATION_FAILED);
					}
				}
				case SHORT -> required(privateAnswer.referenceAnswer());
				case ESSAY -> required(privateAnswer.modelAnswer());
			}
			List<io.edupilot.quiz.RubricCriterion> rubric = privateAnswer.rubric();
			if (rubric != null && !rubric.isEmpty()) {
				BigDecimal sum = rubric.stream()
					.map(io.edupilot.quiz.RubricCriterion::weight)
					.reduce(BigDecimal.ZERO, BigDecimal::add);
				if (sum.compareTo(BigDecimal.ONE) != 0) {
					throw new BusinessException(ErrorCode.VALIDATION_FAILED);
				}
			}
		}
	}

	private Exam requireOwnedExam(Long userId, UserRole role, Long examId) {
		requireInstructor(role);
		Exam exam = examRepository.findWithClassroomById(examId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXAM_NOT_FOUND));
		if (!exam.getInstructorId().equals(userId)) {
			throw new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND);
		}
		return exam;
	}

	private Exam requireOwnedExamForUpdate(Long userId, UserRole role, Long examId) {
		requireInstructor(role);
		Exam exam = examRepository.findByIdForUpdate(examId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXAM_NOT_FOUND));
		if (!exam.getInstructorId().equals(userId)) {
			throw new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND);
		}
		return exam;
	}

	private InstructorExamDetailResponse detailResponse(Exam exam) {
		return InstructorExamDetailResponse.from(
			exam, questionRepository.findByExam_IdOrderByQuestionNo(exam.getId())
		);
	}

	private void validateWeekNumber(Classroom classroom, Integer weekNumber) {
		validateWeekNumber(classroom.getWeekCount(), weekNumber);
	}

	private void validateWeekNumber(int weekCount, Integer weekNumber) {
		if (weekNumber != null && (weekNumber < 1 || weekNumber > weekCount)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private void assertActiveClassroom(Exam exam) {
		if (exam.getClassroomStatus() == ClassroomStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.CLASSROOM_COMPLETED);
		}
	}

	private void assertDraft(Exam exam) {
		if (exam.getStatus() != ExamStatus.DRAFT) {
			throw new BusinessException(ErrorCode.EXAM_NOT_EDITABLE);
		}
	}

	private void requireInstructor(UserRole role) {
		if (role != UserRole.INSTRUCTOR) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
	}

	private String required(String value) {
		return normalizedRequired(value, 10_000);
	}

	private String normalizedRequired(String value, int maxLength) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty() || normalized.length() > maxLength) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}

	private String normalizedOptional(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		if (normalized.length() > maxLength) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}
}
