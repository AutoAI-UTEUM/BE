package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.DocChatPageContextBuilder;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialDocChatContextService;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.material.MaterialPageTextMerger;
import io.edupilot.session.LearningSession;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class QuizDocChatContextServiceTest {

	@Mock
	private MaterialDocChatContextService materialContextService;

	@Mock
	private MaterialPageRepository pageRepository;

	@Mock
	private QuizSubmissionRepository submissionRepository;

	@Test
	void serializesOnlyOwnedMaterialSubmissionsWithAnswersAndPageContext() {
		LearningMaterial material = material();
		when(materialContextService.requireReady(1L, 10L)).thenReturn(material);
		QuizSubmission submission = submission(material);
		when(submissionRepository.findReviewSubmissions(1L, 10L))
			.thenReturn(List.of(submission));
		MaterialPage page = MaterialPage.create(material, 2, "page text");
		page.updateCaption("chart description");
		when(pageRepository.findByMaterial_IdOrderByPageNumberAsc(10L))
			.thenReturn(List.of(page));

		var documents = service().build(1L, 10L);

		assertThat(documents).hasSize(2);
		assertThat(documents.getFirst().text()).contains(
			"Question: What is 2+2?",
			"Choices: a: 3, b: 4",
			"Correct answer: b",
			"Student answer: a",
			"Explanation: 2+2 is 4"
		);
		assertThat(documents.getLast().text())
			.contains("page text", "chart description");
		verify(submissionRepository).findReviewSubmissions(1L, 10L);
	}

	@Test
	void rejectsQuizReviewWhenUserHasNoSubmission() {
		LearningMaterial material = material();
		when(materialContextService.requireReady(1L, 10L)).thenReturn(material);
		when(submissionRepository.findReviewSubmissions(1L, 10L))
			.thenReturn(List.of());

		assertThatThrownBy(() -> service().build(1L, 10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.QUIZ_NOT_FOUND)
			);
	}

	private QuizDocChatContextService service() {
		return new QuizDocChatContextService(
			materialContextService,
			pageRepository,
			submissionRepository,
			new DocChatPageContextBuilder(new MaterialPageTextMerger())
		);
	}

	private LearningMaterial material() {
		User owner = User.create("owner@example.com", "hash", "owner");
		ReflectionTestUtils.setField(owner, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			owner,
			"material",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		material.markReady(2);
		return material;
	}

	private QuizSubmission submission(LearningMaterial material) {
		User user = User.create("student@example.com", "hash", "student");
		ReflectionTestUtils.setField(user, "id", 1L);
		LearningSession session = LearningSession.create(user, material);
		ReflectionTestUtils.setField(session, "id", 20L);
		Quiz quiz = Quiz.create(
			session,
			2,
			"Arithmetic",
			2,
			2,
			QuizType.MCQ,
			List.of(new PublicQuizQuestion(
				"q1",
				"What is 2+2?",
				BigDecimal.TEN,
				List.of(new QuizOption("a", "3"), new QuizOption("b", "4"))
			)),
			List.of(new PrivateQuizQuestion(
				"q1", "b", null, "2+2 is 4", null,
				null, null, null
			)),
			"1.0"
		);
		ReflectionTestUtils.setField(quiz, "id", 30L);
		QuizSubmission submission = QuizSubmission.create(
			quiz,
			user,
			"request-1",
			List.of(new SubmittedAnswer("q1", "a")),
			new GradingResult(
				"1.0",
				BigDecimal.ZERO,
				BigDecimal.TEN,
				List.of(new GradingItem(
					"q1",
					BigDecimal.ZERO,
					BigDecimal.TEN,
					GradingVerdict.WRONG,
					"try again"
				))
			),
			false
		);
		ReflectionTestUtils.setField(submission, "id", 40L);
		return submission;
	}
}
