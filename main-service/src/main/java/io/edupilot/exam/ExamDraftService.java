package io.edupilot.exam;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.ai.dto.ExamDraftRequest;
import io.edupilot.ai.dto.ExamDraftResponse;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.exam.ExamDraftPreparationService.PreparedExamDraft;
import io.edupilot.exam.dto.ExamDraftQuestionsResponse;
import io.edupilot.exam.dto.GenerateExamDraftRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserRole;

@Service
public class ExamDraftService {

	private static final String SCHEMA_VERSION = "1.0";

	private final ExamDraftPreparationService preparationService;
	private final AiClient aiClient;
	private final AiUsageService aiUsageService;

	public ExamDraftService(
		ExamDraftPreparationService preparationService,
		AiClient aiClient,
		AiUsageService aiUsageService
	) {
		this.preparationService = preparationService;
		this.aiClient = aiClient;
		this.aiUsageService = aiUsageService;
	}

	public ExamDraftQuestionsResponse generate(
		Long userId,
		UserRole role,
		Long classroomId,
		Long examId,
		GenerateExamDraftRequest request
	) {
		PreparedExamDraft prepared = preparationService.prepare(
			userId, role, classroomId, examId, request
		);
		ExamDraftResponse response;
		try {
			response = aiClient.generateExamDraft(prepared.aiRequest());
			aiUsageService.record(
				userId,
				AiFeature.EXAM_DRAFT,
				response == null ? null : response.usage(),
				true
			);
		} catch (AiClientException exception) {
			aiUsageService.record(userId, AiFeature.EXAM_DRAFT, null, false);
			throw exception;
		}
		validateResponse(prepared, response);
		return ExamDraftQuestionsResponse.from(response, prepared.truncated());
	}

	private void validateResponse(
		PreparedExamDraft prepared,
		ExamDraftResponse response
	) {
		if (response == null || !SCHEMA_VERSION.equals(response.schemaVersion())
			|| !prepared.aiRequest().examId().equals(response.examId())
			|| response.questions() == null || response.questions().isEmpty()
			|| response.questions().size() > 20 || !validUsage(response.usage())) {
			invalidResponse();
		}

		Map<ExamQuestionType, Integer> expected = new EnumMap<>(ExamQuestionType.class);
		for (ExamDraftRequest.QuestionPlanItem item : prepared.aiRequest().questionPlan()) {
			expected.put(item.questionType(), item.count());
		}
		Map<ExamQuestionType, Integer> actual = new EnumMap<>(ExamQuestionType.class);
		Set<String> questionIds = new HashSet<>();
		for (ExamDraftResponse.Question question : response.questions()) {
			validateQuestion(question, prepared.sourcePageNumbers(), questionIds);
			actual.merge(question.questionType(), 1, Integer::sum);
		}
		if (!expected.equals(actual)) {
			invalidResponse();
		}
	}

	private void validateQuestion(
		ExamDraftResponse.Question question,
		Set<Integer> sourcePageNumbers,
		Set<String> questionIds
	) {
		if (question == null || question.questionType() == null
			|| !hasText(question.questionId()) || !questionIds.add(question.questionId())
			|| !hasText(question.questionText()) || question.points() == null
			|| question.points().signum() <= 0
			|| question.sourcePageNumber() != null
				&& !sourcePageNumbers.contains(question.sourcePageNumber())) {
			invalidResponse();
		}
		switch (question.questionType()) {
			case MCQ -> validateMcq(question);
			case OX -> validateOx(question);
			case SHORT -> validateShort(question);
			case ESSAY -> validateEssay(question);
		}
	}

	private void validateMcq(ExamDraftResponse.Question question) {
		if (!(question instanceof ExamDraftResponse.McqQuestion mcq)
			|| mcq.choices() == null || mcq.choices().size() < 2
			|| !hasText(mcq.answerChoiceId()) || !hasText(mcq.explanation())
			|| mcq.choices().stream().anyMatch(choice -> choice == null
				|| !hasText(choice.choiceId()) || !hasText(choice.text()))
			|| mcq.choices().stream().noneMatch(
				choice -> mcq.answerChoiceId().equals(choice.choiceId())
			)) {
			invalidResponse();
		}
	}

	private void validateOx(ExamDraftResponse.Question question) {
		if (!(question instanceof ExamDraftResponse.OxQuestion ox)
			|| ox.answerValue() == null || !hasText(ox.explanation())) {
			invalidResponse();
		}
	}

	private void validateShort(ExamDraftResponse.Question question) {
		if (!(question instanceof ExamDraftResponse.ShortQuestion shortQuestion)
			|| !hasText(shortQuestion.referenceAnswer())
			|| shortQuestion.gradingCriteria() == null
			|| shortQuestion.gradingCriteria().isEmpty()
			|| shortQuestion.gradingCriteria().stream().anyMatch(
				criterion -> !hasText(criterion)
			)) {
			invalidResponse();
		}
	}

	private void validateEssay(ExamDraftResponse.Question question) {
		if (!(question instanceof ExamDraftResponse.EssayQuestion)) {
			invalidResponse();
		}
		ExamDraftResponse.EssayQuestion essay =
			(ExamDraftResponse.EssayQuestion)question;
		if (!hasText(essay.modelAnswer()) || essay.rubric() == null
			|| essay.rubric().isEmpty()
			|| essay.rubric().stream().anyMatch(item -> item == null
				|| !hasText(item.criterion()) || item.weight() == null
				|| item.weight().signum() <= 0
				|| item.weight().compareTo(BigDecimal.ONE) > 0)) {
			invalidResponse();
		}
		BigDecimal weightSum = essay.rubric().stream()
			.map(ExamDraftResponse.Rubric::weight)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		if (weightSum.compareTo(BigDecimal.ONE) != 0) {
			invalidResponse();
		}
	}

	private boolean validUsage(AiUsage usage) {
		return usage == null || (
			hasText(usage.model())
				&& nonNegative(usage.inputTokens())
				&& nonNegative(usage.outputTokens())
				&& (usage.reasoningTokens() == null || usage.reasoningTokens() >= 0)
		);
	}

	private boolean nonNegative(Long value) {
		return value != null && value >= 0;
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private void invalidResponse() {
		throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
	}
}
