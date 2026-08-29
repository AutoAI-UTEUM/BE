package io.edupilot.exam;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Service
public class ExamAiGradingService {

	private static final Logger log = LoggerFactory.getLogger(ExamAiGradingService.class);
	private static final String SCHEMA_VERSION = "1.0";

	private final AiClient aiClient;
	private final AiUsageService aiUsageService;
	private final ExamSubmissionPersistenceService persistenceService;

	public ExamAiGradingService(
		AiClient aiClient,
		AiUsageService aiUsageService,
		ExamSubmissionPersistenceService persistenceService
	) {
		this.aiClient = aiClient;
		this.aiUsageService = aiUsageService;
		this.persistenceService = persistenceService;
	}

	public ExamAiGradingOutcome grade(Long submissionId) {
		PreparedExamAiGrading prepared = persistenceService.prepareAiGrading(submissionId);
		Map<String, ExamAiGradingOutcome.GradedItem> grades = new HashMap<>();
		boolean failed = false;
		boolean requestInvalid = false;
		for (PreparedExamAiGrading.Group group : prepared.groups()) {
			try {
				GradeResponse response = aiClient.grade(toRequest(prepared.examId(), group));
				aiUsageService.record(
					prepared.userId(),
					AiFeature.GRADE,
					toUsage(response == null ? null : response.usage()),
					true
				);
				grades.putAll(validate(prepared.examId(), group, response));
			} catch (AiClientException exception) {
				aiUsageService.record(
					prepared.userId(),
					AiFeature.GRADE,
					null,
					false
				);
				if ("AI_REQUEST_INVALID".equals(exception.upstreamCode())) {
					requestInvalid = true;
				} else {
					failed = true;
				}
				logFailure(prepared, group, exception);
			} catch (BusinessException exception) {
				failed = true;
				logFailure(prepared, group, exception);
			}
		}

		if (requestInvalid) {
			log.atError()
				.addKeyValue("submissionId", submissionId)
				.addKeyValue("examId", prepared.examId())
				.addKeyValue("failureCode", "AI_REQUEST_INVALID")
				.log("Exam grading request violated the AI contract");
			failed = true;
		}
		return new ExamAiGradingOutcome(Map.copyOf(grades), failed);
	}

	private AiUsage toUsage(GradeResponse.Usage usage) {
		return usage == null ? null : new AiUsage(
			usage.model(),
			usage.inputTokens(),
			usage.outputTokens(),
			usage.reasoningTokens()
		);
	}

	private GradeRequest toRequest(
		Long examId,
		PreparedExamAiGrading.Group group
	) {
		return new GradeRequest(
			SCHEMA_VERSION,
			examId,
			group.questionType().name(),
			group.items().stream()
				.map(item -> new GradeRequest.Item(
					item.questionId(), item.question(), item.modelAnswer(),
					item.rubric(), item.maxScore()
				))
				.toList(),
			group.items().stream()
				.map(item -> new GradeRequest.StudentAnswer(
					item.questionId(), item.studentAnswer()
				))
				.toList(),
			null,
			null
		);
	}

	private Map<String, ExamAiGradingOutcome.GradedItem> validate(
		Long examId,
		PreparedExamAiGrading.Group group,
		GradeResponse response
	) {
		if (response == null
			|| !SCHEMA_VERSION.equals(response.schemaVersion())
			|| !examId.equals(response.quizId())
			|| !group.questionType().name().equals(response.quizType())
			|| response.score() == null
			|| response.maxScore() == null
			|| response.items() == null
			|| response.items().size() != group.items().size()) {
			throw invalidResult();
		}
		Map<String, PreparedExamAiGrading.Item> expected = new HashMap<>();
		for (PreparedExamAiGrading.Item item : group.items()) {
			expected.put(item.questionId(), item);
		}
		Set<String> seen = new HashSet<>();
		Map<String, ExamAiGradingOutcome.GradedItem> result = new HashMap<>();
		BigDecimal score = BigDecimal.ZERO;
		BigDecimal maxScore = BigDecimal.ZERO;
		for (GradeResponse.Item item : response.items()) {
			PreparedExamAiGrading.Item expectedItem = item == null
				? null : expected.get(item.questionId());
			if (item == null || expectedItem == null || !seen.add(item.questionId())
				|| item.score() == null || item.maxScore() == null
				|| item.verdict() == null || item.feedback() == null
				|| item.maxScore().compareTo(expectedItem.maxScore()) != 0
				|| item.score().signum() < 0
				|| item.score().compareTo(item.maxScore()) > 0
				|| hasMoreThanTwoDecimals(item.score())
				|| hasMoreThanTwoDecimals(item.maxScore())) {
				throw invalidResult();
			}
			Verdict verdict;
			try {
				verdict = Verdict.valueOf(item.verdict());
			} catch (IllegalArgumentException exception) {
				throw invalidResult();
			}
			result.put(
				item.questionId(),
				new ExamAiGradingOutcome.GradedItem(
					item.score(), verdict, item.feedback()
				)
			);
			score = score.add(item.score());
			maxScore = maxScore.add(item.maxScore());
		}
		if (seen.size() != expected.size()
			|| response.score().compareTo(score) != 0
			|| response.maxScore().compareTo(maxScore) != 0
			|| hasMoreThanTwoDecimals(response.score())
			|| hasMoreThanTwoDecimals(response.maxScore())) {
			throw invalidResult();
		}
		return Map.copyOf(result);
	}

	private void logFailure(
		PreparedExamAiGrading prepared,
		PreparedExamAiGrading.Group group,
		RuntimeException exception
	) {
		log.atWarn()
			.addKeyValue("submissionId", prepared.submissionId())
			.addKeyValue("examId", prepared.examId())
			.addKeyValue("questionType", group.questionType())
			.addKeyValue("failureType", exception.getClass().getSimpleName())
			.log("Exam AI grading group failed");
	}

	private boolean hasMoreThanTwoDecimals(BigDecimal value) {
		return Math.max(0, value.stripTrailingZeros().scale()) > 2;
	}

	private BusinessException invalidResult() {
		return new BusinessException(ErrorCode.GRADING_RESULT_INVALID);
	}
}
