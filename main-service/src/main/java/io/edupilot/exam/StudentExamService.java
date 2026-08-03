package io.edupilot.exam;

import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomStatus;
import io.edupilot.exam.dto.ExamSubmissionResponse;
import io.edupilot.exam.dto.ExamSubmissionSummaryResponse;
import io.edupilot.exam.dto.StudentExamDetailResponse;
import io.edupilot.exam.dto.StudentExamListItemResponse;
import io.edupilot.exam.dto.StudentExamListResponse;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserRole;

@Service
public class StudentExamService {

	private static final Set<ExamStatus> VISIBLE_STATUSES = Set.of(
		ExamStatus.PUBLISHED, ExamStatus.CLOSED
	);

	private final ClassroomService classroomService;
	private final ExamRepository examRepository;
	private final ExamQuestionRepository questionRepository;
	private final ExamSubmissionRepository submissionRepository;
	private final ExamAnswerRepository answerRepository;
	private final ExamSubmissionPersistenceService persistenceService;

	public StudentExamService(
		ClassroomService classroomService,
		ExamRepository examRepository,
		ExamQuestionRepository questionRepository,
		ExamSubmissionRepository submissionRepository,
		ExamAnswerRepository answerRepository,
		ExamSubmissionPersistenceService persistenceService
	) {
		this.classroomService = classroomService;
		this.examRepository = examRepository;
		this.questionRepository = questionRepository;
		this.submissionRepository = submissionRepository;
		this.answerRepository = answerRepository;
		this.persistenceService = persistenceService;
	}

	@Transactional(readOnly = true)
	public StudentExamListResponse list(
		Long userId,
		UserRole role,
		Long classroomId,
		int page,
		int size
	) {
		requireLearner(role);
		classroomService.requireVisible(userId, role, classroomId);
		PageRequest pageable = PageRequest.of(
			page, size, Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"))
		);
		Page<Exam> exams = examRepository.findByClassroom_IdAndStatusIn(
			classroomId, VISIBLE_STATUSES, pageable
		);
		return new StudentExamListResponse(
			exams.getContent().stream().map(exam -> {
				ExamSubmission latest = submissionRepository
					.findTopByExam_IdAndUser_IdOrderByAttemptNoDesc(exam.getId(), userId)
					.orElse(null);
				return StudentExamListItemResponse.from(
					exam,
					isSubmittable(exam, latest),
					latest == null ? null : ExamSubmissionSummaryResponse.from(latest)
				);
			}).toList(),
			exams.getNumber(), exams.getSize(), exams.getTotalElements(), exams.getTotalPages()
		);
	}

	@Transactional(readOnly = true)
	public StudentExamDetailResponse detail(Long userId, UserRole role, Long examId) {
		Exam exam = requireVisibleExam(userId, role, examId);
		ExamSubmission latest = submissionRepository
			.findTopByExam_IdAndUser_IdOrderByAttemptNoDesc(examId, userId)
			.orElse(null);
		return StudentExamDetailResponse.from(
			exam,
			questionRepository.findByExam_IdOrderByQuestionNo(examId),
			isSubmittable(exam, latest),
			latest == null ? null : ExamSubmissionSummaryResponse.from(latest)
		);
	}

	public ExamSubmissionResponse submit(
		Long userId,
		UserRole role,
		Long examId,
		SubmitExamRequest request
	) {
		requireLearner(role);
		String requestId = request == null || request.requestId() == null
			? null : request.requestId().trim();
		ExamSubmissionResponse existing = findByRequest(examId, userId, requestId);
		if (existing != null) {
			return existing;
		}
		try {
			return persistenceService.create(
				userId, role, examId, request
			);
		} catch (DataIntegrityViolationException exception) {
			ExamSubmissionResponse concurrent = findByRequest(examId, userId, requestId);
			if (concurrent != null) {
				return concurrent;
			}
			return persistenceService.create(userId, role, examId, request);
		}
	}

	private boolean isSubmittable(Exam exam, ExamSubmission latest) {
		return exam.getStatus() == ExamStatus.PUBLISHED
			&& exam.getClassroomStatus() == ClassroomStatus.ACTIVE
			&& (latest == null
				|| latest.getStatus() == SubmissionStatus.GRADING_FAILED
				|| latest.getStatus() == SubmissionStatus.GRADED && exam.isAllowRetake());
	}

	@Transactional(readOnly = true)
	public ExamSubmissionResponse mySubmission(
		Long userId,
		UserRole role,
		Long examId,
		Integer attemptNo
	) {
		requireVisibleExam(userId, role, examId);
		ExamSubmission submission = attemptNo == null
			? submissionRepository.findTopByExam_IdAndUser_IdOrderByAttemptNoDesc(
				examId, userId
			).orElseThrow(() -> new BusinessException(ErrorCode.EXAM_NOT_FOUND))
			: submissionRepository.findByExam_IdAndUser_IdAndAttemptNo(
				examId, userId, attemptNo
			).orElseThrow(() -> new BusinessException(ErrorCode.EXAM_NOT_FOUND));
		return response(submission);
	}

	@Transactional(readOnly = true)
	public ExamSubmissionResponse findByRequest(
		Long examId,
		Long userId,
		String requestId
	) {
		if (requestId == null || requestId.isBlank()) {
			return null;
		}
		return submissionRepository.findByExam_IdAndUser_IdAndRequestId(
			examId, userId, requestId
		).map(this::response).orElse(null);
	}

	private Exam requireVisibleExam(Long userId, UserRole role, Long examId) {
		requireLearner(role);
		Exam exam = examRepository.findWithClassroomById(examId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXAM_NOT_FOUND));
		if (exam.getStatus() == ExamStatus.DRAFT) {
			throw new BusinessException(ErrorCode.EXAM_NOT_FOUND);
		}
		classroomService.requireVisible(userId, role, exam.getClassroomId());
		return exam;
	}

	private ExamSubmissionResponse response(ExamSubmission submission) {
		return ExamSubmissionResponse.from(
			submission,
			answerRepository.findBySubmission_IdOrderByQuestion_Id(submission.getId())
		);
	}

	private void requireLearner(UserRole role) {
		if (role != UserRole.LEARNER) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
	}
}
