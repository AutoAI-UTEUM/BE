package io.edupilot.quiz;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.dto.QuizSubmissionDetailResponse;
import io.edupilot.session.SessionStatus;

@Service
public class QuizQuestionResultService {

	private final QuizSubmissionRepository submissionRepository;

	public QuizQuestionResultService(QuizSubmissionRepository submissionRepository) {
		this.submissionRepository = submissionRepository;
	}

	@Transactional(readOnly = true)
	public QuizQuestionResult requireOwned(Long userId, Long submissionId, String questionId) {
		QuizSubmission submission = submissionRepository.findByIdAndUser_Id(submissionId, userId)
			.filter(candidate -> candidate.getQuiz().getSessionStatus() != SessionStatus.DELETED)
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		Quiz quiz = submission.getQuiz();
		PublicQuizQuestion question = quiz.getPublicQuestions().stream()
			.filter(candidate -> candidate.questionId().equals(questionId))
			.findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		QuizSubmissionDetailResponse.Item result = QuizSubmissionReconstruction.from(submission)
			.toDetailResponse().items().stream()
			.filter(candidate -> candidate.questionId().equals(questionId))
			.findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		return new QuizQuestionResult(
			questionId, question.questionText(), quiz.getQuizType(), question.choices(),
			result.correctAnswer(), result.submittedAnswer()
		);
	}
}
