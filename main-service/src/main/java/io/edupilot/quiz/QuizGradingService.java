package io.edupilot.quiz;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Service
public class QuizGradingService {

	private final AiClient aiClient;

	public QuizGradingService(AiClient aiClient) {
		this.aiClient = aiClient;
	}

	public GradingResult grade(PreparedQuizSubmission prepared) {
		if (!prepared.quizType().usesAiGrading()) {
			return gradeDeterministically(prepared);
		}
		GradeResponse response = aiClient.grade(toGradeRequest(prepared));
		return validateAiResult(prepared, response);
	}

	private GradingResult gradeDeterministically(
		PreparedQuizSubmission prepared
	) {
		Map<String, PrivateQuizQuestion> privateById =
			prepared.privateQuestions().stream().collect(Collectors.toMap(
				PrivateQuizQuestion::questionId,
				Function.identity()
			));
		Map<String, String> answersById = prepared.answers().stream()
			.collect(Collectors.toMap(
				SubmittedAnswer::questionId,
				SubmittedAnswer::answer
			));

		List<GradingItem> items = new ArrayList<>();
		for (PublicQuizQuestion question : prepared.publicQuestions()) {
			PrivateQuizQuestion privateQuestion = privateById.get(
				question.questionId()
			);
			if (privateQuestion == null) {
				throw invalidResult();
			}
			String answer = answersById.get(question.questionId());
			boolean correct = switch (prepared.quizType()) {
				case MCQ -> privateQuestion.answerChoiceId().equals(answer);
				case OX -> Boolean.toString(privateQuestion.answerValue())
					.equals(answer);
				case SHORT, ESSAY -> throw invalidResult();
			};
			items.add(new GradingItem(
				question.questionId(),
				correct ? question.points() : BigDecimal.ZERO,
				question.points(),
				correct ? GradingVerdict.CORRECT : GradingVerdict.WRONG,
				privateQuestion.explanation()
			));
		}
		return result(prepared.schemaVersion(), items);
	}

	private GradeRequest toGradeRequest(PreparedQuizSubmission prepared) {
		Map<String, PrivateQuizQuestion> privateById =
			prepared.privateQuestions().stream().collect(Collectors.toMap(
				PrivateQuizQuestion::questionId,
				Function.identity()
			));
		List<GradeRequest.Item> items = prepared.publicQuestions().stream()
			.map(question -> {
				PrivateQuizQuestion privateQuestion = privateById.get(
					question.questionId()
				);
				if (privateQuestion == null) {
					throw invalidResult();
				}
				String modelAnswer = prepared.quizType() == QuizType.SHORT
					? privateQuestion.referenceAnswer()
					: privateQuestion.modelAnswer();
				List<GradeRequest.Rubric> rubric = gradeRubric(
					prepared.quizType(),
					privateQuestion
				);
				return new GradeRequest.Item(
					question.questionId(),
					question.questionText(),
					modelAnswer,
					rubric,
					question.points()
				);
			})
			.toList();
		List<GradeRequest.StudentAnswer> studentAnswers = prepared.answers()
			.stream()
			.map(answer -> new GradeRequest.StudentAnswer(
				answer.questionId(),
				answer.answer()
			))
			.toList();
		return new GradeRequest(
			prepared.schemaVersion(),
			prepared.quizId(),
			prepared.quizType().name(),
			items,
			studentAnswers,
			prepared.pageContext(),
			null
		);
	}

	private List<GradeRequest.Rubric> gradeRubric(
		QuizType quizType,
		PrivateQuizQuestion question
	) {
		if (quizType == QuizType.ESSAY) {
			return question.rubric().stream()
				.map(criterion -> new GradeRequest.Rubric(
					criterion.criterion(),
					criterion.weight()
				))
				.toList();
		}
		List<String> criteria = question.gradingCriteria();
		if (criteria == null || criteria.isEmpty()) {
			throw invalidResult();
		}
		BigDecimal unitWeight = BigDecimal.ONE.divide(
			BigDecimal.valueOf(criteria.size()),
			8,
			java.math.RoundingMode.HALF_UP
		);
		List<GradeRequest.Rubric> rubric = new ArrayList<>();
		BigDecimal assignedWeight = BigDecimal.ZERO;
		for (int index = 0; index < criteria.size(); index++) {
			BigDecimal weight = index == criteria.size() - 1
				? BigDecimal.ONE.subtract(assignedWeight)
				: unitWeight;
			rubric.add(new GradeRequest.Rubric(criteria.get(index), weight));
			assignedWeight = assignedWeight.add(weight);
		}
		return List.copyOf(rubric);
	}

	private GradingResult validateAiResult(
		PreparedQuizSubmission prepared,
		GradeResponse response
	) {
		if (response == null
			|| !prepared.schemaVersion().equals(response.schemaVersion())
			|| !prepared.quizId().equals(response.quizId())
			|| !prepared.quizType().name().equals(response.quizType())
			|| response.score() == null
			|| response.maxScore() == null
			|| response.items() == null
			|| response.items().size() != prepared.publicQuestions().size()) {
			throw invalidResult();
		}

		Map<String, PublicQuizQuestion> questionsById =
			prepared.publicQuestions().stream().collect(Collectors.toMap(
				PublicQuizQuestion::questionId,
				Function.identity()
			));
		Set<String> seenQuestionIds = new HashSet<>();
		List<GradingItem> items = new ArrayList<>();
		for (GradeResponse.Item item : response.items()) {
			if (item == null
				|| !StringUtils.hasText(item.questionId())
				|| !seenQuestionIds.add(item.questionId())
				|| item.score() == null
				|| item.maxScore() == null
				|| !StringUtils.hasText(item.verdict())
				|| item.feedback() == null) {
				throw invalidResult();
			}
			PublicQuizQuestion question = questionsById.get(item.questionId());
			if (question == null
				|| item.maxScore().compareTo(question.points()) != 0
				|| item.score().compareTo(BigDecimal.ZERO) < 0
				|| item.score().compareTo(item.maxScore()) > 0
				|| hasMoreThanTwoDecimals(item.score())
				|| hasMoreThanTwoDecimals(item.maxScore())) {
				throw invalidResult();
			}
			GradingVerdict verdict;
			try {
				verdict = GradingVerdict.valueOf(item.verdict());
			} catch (IllegalArgumentException exception) {
				throw invalidResult();
			}
			items.add(new GradingItem(
				item.questionId(),
				item.score(),
				item.maxScore(),
				verdict,
				item.feedback()
			));
		}

		GradingResult result = result(prepared.schemaVersion(), items);
		if (seenQuestionIds.size() != questionsById.size()
			|| response.score().compareTo(result.score()) != 0
			|| response.maxScore().compareTo(result.maxScore()) != 0
			|| hasMoreThanTwoDecimals(response.score())
			|| hasMoreThanTwoDecimals(response.maxScore())) {
			throw invalidResult();
		}
		return result;
	}

	private GradingResult result(
		String schemaVersion,
		List<GradingItem> items
	) {
		BigDecimal score = items.stream()
			.map(GradingItem::score)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal maxScore = items.stream()
			.map(GradingItem::maxScore)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		if (maxScore.compareTo(BigDecimal.ZERO) <= 0) {
			throw invalidResult();
		}
		return new GradingResult(
			schemaVersion,
			score,
			maxScore,
			List.copyOf(items)
		);
	}

	private boolean hasMoreThanTwoDecimals(BigDecimal value) {
		return Math.max(0, value.stripTrailingZeros().scale()) > 2;
	}

	private BusinessException invalidResult() {
		return new BusinessException(ErrorCode.GRADING_RESULT_INVALID);
	}
}
