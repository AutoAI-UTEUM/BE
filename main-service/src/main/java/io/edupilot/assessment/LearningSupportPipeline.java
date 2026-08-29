package io.edupilot.assessment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.DiagnosisRequest;
import io.edupilot.ai.dto.DiagnosisResponse;
import io.edupilot.ai.dto.QuizAssessmentRequest;
import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiQuotaService;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.diagnosis.DiagnosisPersistenceService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.memory.LearnerMemoryRepository;
import io.edupilot.quiz.GradingItem;
import io.edupilot.quiz.PrivateQuizQuestion;
import io.edupilot.quiz.PublicQuizQuestion;
import io.edupilot.quiz.QuizPostGradingContext;
import io.edupilot.quiz.QuizPostGradingHook;
import io.edupilot.quiz.QuizType;
import io.edupilot.session.UiAction;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Component
public class LearningSupportPipeline implements QuizPostGradingHook {

	private static final Logger log =
		LoggerFactory.getLogger(LearningSupportPipeline.class);

	private final AiClient aiClient;
	private final AiUsageService aiUsageService;
	private final AiQuotaService aiQuotaService;
	private final UserRepository userRepository;
	private final AssessmentPersistenceService assessmentPersistenceService;
	private final DiagnosisPersistenceService diagnosisPersistenceService;
	private final LearnerMemoryRepository memoryRepository;

	public LearningSupportPipeline(
		AiClient aiClient,
		AiUsageService aiUsageService,
		AiQuotaService aiQuotaService,
		UserRepository userRepository,
		AssessmentPersistenceService assessmentPersistenceService,
		DiagnosisPersistenceService diagnosisPersistenceService,
		LearnerMemoryRepository memoryRepository
	) {
		this.aiClient = aiClient;
		this.aiUsageService = aiUsageService;
		this.aiQuotaService = aiQuotaService;
		this.userRepository = userRepository;
		this.assessmentPersistenceService = assessmentPersistenceService;
		this.diagnosisPersistenceService = diagnosisPersistenceService;
		this.memoryRepository = memoryRepository;
	}

	@Override
	public List<UiAction> onGraded(QuizPostGradingContext context) {
		String memoryDigest = memoryRepository.findByUser_IdAndMaterial_Id(
				context.userId(),
				context.materialId()
			)
			.map(memory -> memory.getMemoryDigest())
			.orElse(null);
		QuizAssessmentRequest assessmentRequest = assessmentRequest(
			context,
			memoryDigest
		);
		QuizAssessmentResponse assessmentResponse;
		try {
			User user = activeUser(context.userId());
			aiQuotaService.checkQuota(context.userId(), user.getRole());
			assessmentResponse = aiClient.quizAssessment(assessmentRequest);
			aiUsageService.record(
				context.userId(),
				AiFeature.QUIZ_ASSESSMENT,
				assessmentResponse == null ? null : assessmentResponse.usage(),
				true
			);
			AssessmentPersistenceService.AssessmentSaveResult result =
				assessmentPersistenceService.save(
					context,
					assessmentResponse
				);
			if (!result.applied()) {
				log.atInfo()
					.addKeyValue("submissionId", context.submissionId())
					.addKeyValue("sessionId", context.sessionId())
					.log(
						"Quiz assessment discarded after session state changed"
					);
				return defaultActions(context);
			}
		} catch (RuntimeException exception) {
			recordFailure(context.userId(), AiFeature.QUIZ_ASSESSMENT, exception);
			rethrowQuotaExceeded(exception);
			logFailure("assessment", context, exception);
			return defaultActions(context);
		}

		if (context.passed()) {
			return defaultActions(context);
		}

		try {
			DiagnosisRequest request = diagnosisRequest(
				context,
				assessmentRequest,
				assessmentResponse,
				memoryDigest
			);
			if (request.wrongItems().isEmpty()) {
				log.atWarn()
					.addKeyValue("submissionId", context.submissionId())
					.addKeyValue("quizId", context.quizId())
					.log("Diagnosis skipped because wrongItems is empty");
				return defaultActions(context);
			}
			User user = activeUser(context.userId());
			aiQuotaService.checkQuota(context.userId(), user.getRole());
			DiagnosisResponse response = aiClient.diagnosis(request);
			aiUsageService.record(
				context.userId(),
				AiFeature.DIAGNOSIS,
				response == null ? null : response.usage(),
				true
			);
			return diagnosisPersistenceService.savePending(context, response)
				.map(List::of)
				.orElseGet(() -> defaultActions(context));
		} catch (RuntimeException exception) {
			recordFailure(context.userId(), AiFeature.DIAGNOSIS, exception);
			rethrowQuotaExceeded(exception);
			logFailure("diagnosis", context, exception);
			return defaultActions(context);
		}
	}

	private QuizAssessmentRequest assessmentRequest(
		QuizPostGradingContext context,
		String memoryDigest
	) {
		Map<String, PrivateQuizQuestion> privateById = new HashMap<>();
		for (PrivateQuizQuestion question : context.privateQuestions()) {
			privateById.put(question.questionId(), question);
		}
		List<QuizAssessmentRequest.QuizItem> items = new ArrayList<>();
		Map<String, PublicQuizQuestion> publicById = new HashMap<>();
		for (PublicQuizQuestion question : context.publicQuestions()) {
			publicById.put(question.questionId(), question);
			PrivateQuizQuestion privateQuestion =
				privateById.get(question.questionId());
			items.add(new QuizAssessmentRequest.QuizItem(
				question.questionId(),
				question.questionText(),
				modelAnswer(context, question, privateQuestion),
				question.points()
			));
		}
		return new QuizAssessmentRequest(
			"1.0",
			new QuizAssessmentRequest.QuizResult(
				context.quizId(),
				context.quizType().name(),
				context.gradingResult().score(),
				context.gradingResult().maxScore(),
				context.passed(),
				context.gradingResult().items().stream()
					.map(this::resultItem)
					.toList()
			),
			items,
			context.answers().stream()
				.map(answer ->
					new QuizAssessmentRequest.StudentAnswer(
						answer.questionId(),
						answerText(
							context.quizType(),
							publicById.get(answer.questionId()),
							answer.answer()
						)
					))
				.toList(),
			new QuizAssessmentRequest.PageContext(
				context.pageContext().coverageStartPage(),
				context.pageContext().coverageEndPage(),
				context.pageContext().text()
			),
			memoryDigest
		);
	}

	private DiagnosisRequest diagnosisRequest(
		QuizPostGradingContext context,
		QuizAssessmentRequest assessmentRequest,
		QuizAssessmentResponse assessmentResponse,
		String memoryDigest
	) {
		Map<String, QuizAssessmentRequest.QuizItem> quizItems = new HashMap<>();
		for (QuizAssessmentRequest.QuizItem item
			: assessmentRequest.quizItems()) {
			quizItems.put(item.questionId(), item);
		}
		Map<String, QuizAssessmentRequest.StudentAnswer> answers = new HashMap<>();
		for (QuizAssessmentRequest.StudentAnswer answer
			: assessmentRequest.studentAnswers()) {
			answers.put(answer.questionId(), answer);
		}
		List<DiagnosisRequest.WrongItem> wrongItems =
			context.gradingResult().items().stream()
				.filter(item -> item.verdict()
					!= io.edupilot.quiz.GradingVerdict.CORRECT)
				.map(item -> {
					QuizAssessmentRequest.QuizItem quizItem =
						quizItems.get(item.questionId());
					QuizAssessmentRequest.StudentAnswer answer =
						answers.get(item.questionId());
					return new DiagnosisRequest.WrongItem(
						item.questionId(),
						quizItem.question(),
						answer.answer(),
						quizItem.modelAnswer(),
						item.feedback()
					);
				})
				.toList();
		return new DiagnosisRequest(
			"1.0",
			assessmentResponse,
			assessmentRequest.quizResult(),
			wrongItems,
			assessmentRequest.pageContext(),
			memoryDigest
		);
	}

	private QuizAssessmentRequest.ResultItem resultItem(GradingItem item) {
		return new QuizAssessmentRequest.ResultItem(
			item.questionId(),
			item.score(),
			item.maxScore(),
			item.verdict().name(),
			item.feedback()
		);
	}

	private String modelAnswer(
		QuizPostGradingContext context,
		PublicQuizQuestion publicQuestion,
		PrivateQuizQuestion question
	) {
		return switch (context.quizType()) {
			case MCQ -> answerText(
				QuizType.MCQ,
				publicQuestion,
				question.answerChoiceId()
			);
			case OX -> answerText(
				QuizType.OX,
				publicQuestion,
				String.valueOf(question.answerValue())
			);
			case SHORT -> question.referenceAnswer();
			case ESSAY -> question.modelAnswer();
		};
	}

	private String answerText(
		QuizType quizType,
		PublicQuizQuestion question,
		String answer
	) {
		return switch (quizType) {
			case MCQ -> question.choices().stream()
				.filter(choice -> choice.choiceId().equals(answer))
				.findFirst()
				.map(choice -> "%s: %s".formatted(
					choice.choiceId(),
					choice.text()
				))
				.orElse(answer);
			case OX -> "true".equals(answer)
				? "O (true)"
				: "X (false)";
			case SHORT, ESSAY -> answer;
		};
	}

	private List<UiAction> defaultActions(QuizPostGradingContext context) {
		return context.defaultUiActions();
	}

	private void logFailure(
		String stage,
		QuizPostGradingContext context,
		RuntimeException exception
	) {
		String errorCode = exception instanceof AiClientException aiException
			? aiException.errorCode().code()
			: "PIPELINE_PERSISTENCE_FAILED";
		log.atWarn()
			.addKeyValue("stage", stage)
			.addKeyValue("submissionId", context.submissionId())
			.addKeyValue("sessionId", context.sessionId())
			.addKeyValue("errorCode", errorCode)
			.log("Quiz learning-support stage failed");
	}

	private User activeUser(Long userId) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		return user;
	}

	private void recordFailure(
		Long userId,
		AiFeature feature,
		RuntimeException exception
	) {
		if (exception instanceof AiClientException) {
			aiUsageService.record(userId, feature, null, false);
		}
	}

	private void rethrowQuotaExceeded(RuntimeException exception) {
		if (exception instanceof BusinessException businessException
			&& businessException.errorCode() == ErrorCode.AI_QUOTA_EXCEEDED) {
			throw businessException;
		}
	}
}
