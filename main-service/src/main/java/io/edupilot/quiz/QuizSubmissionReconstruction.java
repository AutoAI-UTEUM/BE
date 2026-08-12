package io.edupilot.quiz;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.edupilot.quiz.dto.QuizGradingResultResponse;
import io.edupilot.quiz.dto.QuizSubmissionDetailResponse;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.edupilot.session.UiAction;

record QuizSubmissionReconstruction(
	Long submissionId,
	Long quizId,
	QuizType quizType,
	Instant submittedAt,
	BigDecimal score,
	BigDecimal maxScore,
	boolean passed,
	GradingResult gradingResult,
	List<SubmittedAnswer> submittedAnswers,
	List<PrivateQuizQuestion> privateQuestions
) {

	static QuizSubmissionReconstruction from(QuizSubmission submission) {
		return new QuizSubmissionReconstruction(
			submission.getId(),
			submission.getQuizId(),
			submission.getQuizType(),
			submission.getCreatedAt(),
			submission.getScore(),
			submission.getMaxScore(),
			submission.isPassed(),
			submission.getGradingResult(),
			submission.getSubmittedAnswers(),
			submission.getPrivateQuestions()
		);
	}

	QuizSubmitResponse toSubmitResponse(List<UiAction> uiActions) {
		return new QuizSubmitResponse(
			submissionId,
			quizId,
			quizType,
			score,
			maxScore,
			passed,
			QuizGradingResultResponse.from(gradingResult),
			List.copyOf(uiActions)
		);
	}

	QuizSubmissionDetailResponse toDetailResponse() {
		Map<String, SubmittedAnswer> answersByQuestionId = submittedAnswers
			.stream()
			.collect(Collectors.toMap(
				SubmittedAnswer::questionId,
				Function.identity()
			));
		Map<String, PrivateQuizQuestion> privateByQuestionId = privateQuestions
			.stream()
			.collect(Collectors.toMap(
				PrivateQuizQuestion::questionId,
				Function.identity()
			));
		List<QuizSubmissionDetailResponse.Item> items = gradingResult.items()
			.stream()
			.map(item -> detailItem(
				item,
				requireAnswer(answersByQuestionId, item.questionId()),
				requirePrivateQuestion(
					privateByQuestionId,
					item.questionId()
				)
			))
			.toList();

		return new QuizSubmissionDetailResponse(
			quizId,
			submissionId,
			submittedAt,
			score,
			maxScore,
			passed,
			items
		);
	}

	private QuizSubmissionDetailResponse.Item detailItem(
		GradingItem item,
		SubmittedAnswer answer,
		PrivateQuizQuestion privateQuestion
	) {
		return new QuizSubmissionDetailResponse.Item(
			item.questionId(),
			answer.answer(),
			correctAnswer(privateQuestion),
			item.verdict(),
			item.score(),
			item.maxScore(),
			item.feedback(),
			privateQuestion.explanation()
		);
	}

	private String correctAnswer(PrivateQuizQuestion question) {
		return switch (quizType) {
			case MCQ -> question.answerChoiceId();
			case OX -> Boolean.toString(question.answerValue());
			case SHORT -> question.referenceAnswer();
			case ESSAY -> question.modelAnswer();
		};
	}

	private SubmittedAnswer requireAnswer(
		Map<String, SubmittedAnswer> answers,
		String questionId
	) {
		SubmittedAnswer answer = answers.get(questionId);
		if (answer == null) {
			throw new IllegalStateException(
				"Stored submission answer is incomplete"
			);
		}
		return answer;
	}

	private PrivateQuizQuestion requirePrivateQuestion(
		Map<String, PrivateQuizQuestion> questions,
		String questionId
	) {
		PrivateQuizQuestion question = questions.get(questionId);
		if (question == null) {
			throw new IllegalStateException(
				"Stored quiz answer data is incomplete"
			);
		}
		return question;
	}
}
