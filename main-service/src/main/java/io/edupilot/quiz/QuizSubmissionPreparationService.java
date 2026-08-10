package io.edupilot.quiz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.session.SessionStatus;
import tools.jackson.databind.JsonNode;

@Service
public class QuizSubmissionPreparationService {

	private final QuizRepository quizRepository;
	private final MaterialPageRepository materialPageRepository;

	public QuizSubmissionPreparationService(
		QuizRepository quizRepository,
		MaterialPageRepository materialPageRepository
	) {
		this.quizRepository = quizRepository;
		this.materialPageRepository = materialPageRepository;
	}

	@Transactional(readOnly = true)
	public PreparedQuizSubmission prepare(
		Long userId,
		Long quizId,
		QuizSubmitRequest request
	) {
		Quiz quiz = quizRepository.findOwned(quizId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		if (quiz.getSessionStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.QUIZ_NOT_SUBMITTABLE);
		}
		if (!Objects.equals(quiz.getSessionActiveQuizId(), quizId)) {
			throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
		}
		if (request == null
			|| !StringUtils.hasText(request.requestId())
			|| request.requestId().length() > 255) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}

		List<PublicQuizQuestion> publicQuestions = quiz.getPublicQuestions();
		List<SubmittedAnswer> answers = validateAnswers(
			quiz.getQuizType(),
			publicQuestions,
			request.answers()
		);
		GradeRequest.PageContext pageContext = pageContext(quiz);

		return new PreparedQuizSubmission(
			quiz.getId(),
			quiz.getSessionId(),
			quiz.getMaterialId(),
			quiz.getQuizType(),
			quiz.getSchemaVersion(),
			request.requestId().trim(),
			publicQuestions,
			quiz.getPrivateQuestions(),
			answers,
			pageContext
		);
	}

	private List<SubmittedAnswer> validateAnswers(
		QuizType quizType,
		List<PublicQuizQuestion> questions,
		List<QuizSubmitRequest.Answer> requestedAnswers
	) {
		if (requestedAnswers == null
			|| requestedAnswers.size() != questions.size()) {
			throw invalidAnswer();
		}

		Map<String, PublicQuizQuestion> questionsById = questions.stream()
			.collect(Collectors.toMap(PublicQuizQuestion::questionId, question -> question));
		Map<String, String> answersById = new HashMap<>();
		for (QuizSubmitRequest.Answer answer : requestedAnswers) {
			if (answer == null
				|| !StringUtils.hasText(answer.questionId())
				|| answer.answer() == null
				|| !answer.answer().isString()
				|| !questionsById.containsKey(answer.questionId())
				|| answersById.put(
					answer.questionId(),
					answer.answer().stringValue()
				) != null) {
				throw invalidAnswer();
			}
		}

		List<SubmittedAnswer> ordered = new ArrayList<>();
		for (PublicQuizQuestion question : questions) {
			String answer = answersById.get(question.questionId());
			if (answer == null) {
				throw invalidAnswer();
			}
			validateAnswerFormat(quizType, question, answer);
			ordered.add(new SubmittedAnswer(question.questionId(), answer));
		}
		return List.copyOf(ordered);
	}

	private void validateAnswerFormat(
		QuizType quizType,
		PublicQuizQuestion question,
		String answer
	) {
		switch (quizType) {
			case MCQ -> {
				Set<String> optionIds = question.choices() == null
					? Set.of()
					: question.choices().stream()
						.map(QuizOption::choiceId)
						.collect(Collectors.toCollection(HashSet::new));
				if (!optionIds.contains(answer)) {
					throw invalidAnswer();
				}
			}
			case OX -> {
				if (!"true".equals(answer) && !"false".equals(answer)) {
					throw invalidAnswer();
				}
			}
			case SHORT, ESSAY -> {
				// Empty textual answers are still submitted to the GraderAgent,
				// which owns SHORT/ESSAY correctness decisions.
			}
		}
	}

	private GradeRequest.PageContext pageContext(Quiz quiz) {
		List<MaterialPage> pages = materialPageRepository
			.findByMaterial_IdAndPageNumberBetweenOrderByPageNumberAsc(
				quiz.getMaterialId(),
				quiz.getCoverageStartPage(),
				quiz.getCoverageEndPage()
			);
		String text = pages.stream()
			.map(page -> "[Page %d]%n%s".formatted(
				page.getPageNumber(),
				page.getTextContent()
			))
			.collect(Collectors.joining("\n\n"));
		return new GradeRequest.PageContext(
			quiz.getCoverageStartPage(),
			quiz.getCoverageEndPage(),
			text
		);
	}

	private BusinessException invalidAnswer() {
		return new BusinessException(ErrorCode.INVALID_QUIZ_ANSWER);
	}
}
