package io.edupilot.quiz;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.DocChatRequest.ContextDocument;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.DocChatPageContextBuilder;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialDocChatContextService;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;

@Service
public class QuizDocChatContextService {

	private final MaterialDocChatContextService materialContextService;
	private final MaterialPageRepository pageRepository;
	private final QuizSubmissionRepository submissionRepository;
	private final DocChatPageContextBuilder pageContextBuilder;

	public QuizDocChatContextService(
		MaterialDocChatContextService materialContextService,
		MaterialPageRepository pageRepository,
		QuizSubmissionRepository submissionRepository,
		DocChatPageContextBuilder pageContextBuilder
	) {
		this.materialContextService = materialContextService;
		this.pageRepository = pageRepository;
		this.submissionRepository = submissionRepository;
		this.pageContextBuilder = pageContextBuilder;
	}

	@Transactional(readOnly = true)
	public List<ContextDocument> build(Long userId, Long materialId) {
		LearningMaterial material = materialContextService.requireReady(
			userId,
			materialId
		);
		List<QuizSubmission> submissions = submissionRepository
			.findReviewSubmissions(userId, materialId);
		if (submissions.isEmpty()) {
			throw new BusinessException(ErrorCode.QUIZ_NOT_FOUND);
		}

		List<ContextDocument> documents = new ArrayList<>();
		documents.add(new ContextDocument(
			material.getTitle() + " quiz review",
			serializeSubmissions(submissions)
		));
		Set<Integer> relatedPages = relatedPages(submissions);
		List<MaterialPage> pages = pageRepository
			.findByMaterial_IdOrderByPageNumberAsc(materialId)
			.stream()
			.filter(page -> relatedPages.contains(page.getPageNumber()))
			.toList();
		documents.addAll(pageContextBuilder.build(
			material.getTitle(),
			pages,
			9
		));
		return List.copyOf(documents);
	}

	private String serializeSubmissions(List<QuizSubmission> submissions) {
		StringBuilder text = new StringBuilder();
		for (QuizSubmission submission : submissions) {
			Quiz quiz = submission.getQuiz();
			Map<String, PrivateQuizQuestion> privateQuestions = submission
				.getPrivateQuestions().stream()
				.collect(Collectors.toMap(
					PrivateQuizQuestion::questionId,
					Function.identity()
				));
			Map<String, SubmittedAnswer> submittedAnswers = submission
				.getSubmittedAnswers().stream()
				.collect(Collectors.toMap(
					SubmittedAnswer::questionId,
					Function.identity()
				));
			Map<String, GradingItem> gradingItems = submission.getGradingResult()
				.items().stream()
				.collect(Collectors.toMap(
					GradingItem::questionId,
					Function.identity()
				));

			text.append("## ").append(quiz.getTitle())
				.append(" (attempt ").append(submission.getAttemptNo())
				.append(", ").append(quiz.getQuizType()).append(")\n");
			for (PublicQuizQuestion question : quiz.getPublicQuestions()) {
				PrivateQuizQuestion privateQuestion = privateQuestions.get(
					question.questionId()
				);
				SubmittedAnswer submittedAnswer = submittedAnswers.get(
					question.questionId()
				);
				GradingItem gradingItem = gradingItems.get(question.questionId());
				text.append("- Question: ").append(question.questionText()).append('\n');
				if (question.choices() != null) {
					text.append("  Choices: ")
						.append(question.choices().stream()
							.map(choice -> choice.choiceId() + ": " + choice.text())
							.collect(Collectors.joining(", ")))
						.append('\n');
				}
				appendValue(text, "Correct answer", correctAnswer(
					quiz.getQuizType(),
					privateQuestion
				));
				appendValue(text, "Student answer", submittedAnswer == null
					? null
					: submittedAnswer.answer());
				appendValue(text, "Verdict", gradingItem == null
					? null
					: gradingItem.verdict().name());
				appendValue(text, "Explanation", privateQuestion == null
					? null
					: privateQuestion.explanation());
			}
			text.append('\n');
		}
		return text.toString().trim();
	}

	private Set<Integer> relatedPages(List<QuizSubmission> submissions) {
		Set<Integer> pages = new LinkedHashSet<>();
		for (QuizSubmission submission : submissions) {
			Quiz quiz = submission.getQuiz();
			for (int page = quiz.getCoverageStartPage();
				page <= quiz.getCoverageEndPage(); page++) {
				pages.add(page);
			}
		}
		return pages;
	}

	private String correctAnswer(
		QuizType quizType,
		PrivateQuizQuestion question
	) {
		if (question == null) {
			return null;
		}
		return switch (quizType) {
			case MCQ -> question.answerChoiceId();
			case OX -> question.answerValue() == null
				? null
				: question.answerValue().toString();
			case SHORT -> question.referenceAnswer();
			case ESSAY -> question.modelAnswer();
		};
	}

	private void appendValue(StringBuilder text, String label, String value) {
		if (value != null && !value.isBlank()) {
			text.append("  ").append(label).append(": ")
				.append(value).append('\n');
		}
	}
}
