package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.edupilot.exam.ExamAnswer;
import io.edupilot.exam.ExamPrivateAnswer;
import io.edupilot.exam.ExamQuestionType;
import io.edupilot.exam.ExamSubmission;
import io.edupilot.exam.SubmissionStatus;
import io.edupilot.exam.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;

public record StudentExamSubmissionResponse(
	Long submissionId,
	int attemptNo,
	SubmissionStatus status,
	boolean reviewAvailable,
	BigDecimal score,
	BigDecimal maxScore,
	BigDecimal normalizedScore,
	Instant startedAt,
	Integer durationSeconds,
	Instant submittedAt,
	Instant gradedAt,
	List<AnswerItem> items
) {
	public static StudentExamSubmissionResponse from(
		ExamSubmission submission,
		List<ExamAnswer> answers,
		boolean reviewAvailable
	) {
		boolean revealResult = submission.getStatus() != SubmissionStatus.SUBMITTED;
		return new StudentExamSubmissionResponse(
			submission.getId(),
			submission.getAttemptNo(),
			submission.getStatus(),
			reviewAvailable,
			submission.getScore(),
			submission.getMaxScore(),
			submission.getNormalizedScore(),
			submission.getStartedAt(),
			submission.getDurationSeconds(),
			submission.getSubmittedAt(),
			submission.getGradedAt(),
			answers.stream()
				.map(answer -> item(answer, revealResult, reviewAvailable))
				.toList()
		);
	}

	private static AnswerItem item(
		ExamAnswer answer,
		boolean revealResult,
		boolean reviewAvailable
	) {
		BigDecimal score = revealResult ? answer.effectiveScore() : null;
		Verdict verdict = revealResult ? answer.getVerdict() : null;
		String feedback = revealResult ? answer.getFeedback() : null;
		if (!reviewAvailable) {
			return new ResultItem(
				questionId(answer), answer.getAnswer(), score, answer.getMaxScore(),
				verdict, feedback
			);
		}
		return new ReviewItem(
			questionId(answer), answer.getAnswer(), score, answer.getMaxScore(),
			verdict, feedback, correctAnswer(answer), answer.getPrivateAnswer().explanation()
		);
	}

	private static String questionId(ExamAnswer answer) {
		return "q" + answer.getQuestionNo();
	}

	private static Object correctAnswer(ExamAnswer answer) {
		ExamPrivateAnswer privateAnswer = answer.getPrivateAnswer();
		return switch (answer.getQuestionType()) {
			case MCQ -> {
				String choiceId = privateAnswer.answerChoiceId();
				String text = answer.getPublicQuestion().options() == null
					? null
					: answer.getPublicQuestion().options().stream()
						.filter(option -> option.choiceId().equals(choiceId))
						.map(option -> option.text())
						.findFirst()
						.orElse(null);
				yield new CorrectChoice(choiceId, text);
			}
			case OX -> privateAnswer.answerValue();
			case SHORT, ESSAY -> preferredWrittenAnswer(privateAnswer);
		};
	}

	private static String preferredWrittenAnswer(ExamPrivateAnswer privateAnswer) {
		return privateAnswer.modelAnswer() == null || privateAnswer.modelAnswer().isBlank()
			? privateAnswer.referenceAnswer()
			: privateAnswer.modelAnswer();
	}

	@Schema(oneOf = {ResultItem.class, ReviewItem.class})
	public sealed interface AnswerItem permits ResultItem, ReviewItem {
		String questionId();
		String answer();
		BigDecimal score();
		BigDecimal maxScore();
		Verdict verdict();
		String feedback();
	}

	public record ResultItem(
		String questionId,
		String answer,
		BigDecimal score,
		BigDecimal maxScore,
		Verdict verdict,
		String feedback
	) implements AnswerItem {
	}

	public record ReviewItem(
		String questionId,
		String answer,
		BigDecimal score,
		BigDecimal maxScore,
		Verdict verdict,
		String feedback,
		Object correctAnswer,
		String explanation
	) implements AnswerItem {
	}

	public record CorrectChoice(String choiceId, String text) {
	}
}
