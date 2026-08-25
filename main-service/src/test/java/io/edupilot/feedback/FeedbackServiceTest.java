package io.edupilot.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.feedback.dto.CreateFeedbackRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

	@Mock
	private FeedbackRepository feedbackRepository;

	@Mock
	private UserRepository userRepository;

	private FeedbackService feedbackService;
	private User user;

	@BeforeEach
	void setUp() {
		feedbackService = new FeedbackService(feedbackRepository, userRepository);
		user = User.create("user@example.com", "hash", "학습자");
		ReflectionTestUtils.setField(user, "id", 1L);
	}

	@Test
	void savesFeedbackWithAuthenticatedUserAndOptionalClientContext() {
		when(userRepository.getReferenceById(1L)).thenReturn(user);
		when(feedbackRepository.saveAndFlush(any(Feedback.class)))
			.thenAnswer(invocation -> persisted(invocation.getArgument(0)));

		var response = feedbackService.create(
			1L,
			new CreateFeedbackRequest(
				FeedbackCategory.BUG,
				"채팅 화면이 멈춥니다.",
				"https://app.example/sessions/10",
				"web-1.2.3"
			)
		);

		assertThat(response.feedbackId()).isEqualTo(100L);
		assertThat(response.createdAt()).isEqualTo(NOW);
		ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
		verify(feedbackRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getUserId()).isEqualTo(1L);
		assertThat(captor.getValue().getCategory()).isEqualTo(FeedbackCategory.BUG);
		assertThat(captor.getValue().getMessage()).isEqualTo("채팅 화면이 멈춥니다.");
		assertThat(captor.getValue().getPageUrl())
			.isEqualTo("https://app.example/sessions/10");
		assertThat(captor.getValue().getClientVersion()).isEqualTo("web-1.2.3");
	}

	@Test
	void rejectsMissingCategoryBlankMessageAndOversizedMessage() {
		assertValidationFailed(new CreateFeedbackRequest(
			null,
			"내용",
			null,
			null
		));
		assertValidationFailed(new CreateFeedbackRequest(
			FeedbackCategory.GENERAL,
			" ",
			null,
			null
		));
		assertValidationFailed(new CreateFeedbackRequest(
			FeedbackCategory.GENERAL,
			"a".repeat(2001),
			null,
			null
		));
	}

	private Feedback persisted(Feedback feedback) {
		ReflectionTestUtils.setField(feedback, "id", 100L);
		ReflectionTestUtils.setField(feedback, "createdAt", NOW);
		return feedback;
	}

	private void assertValidationFailed(CreateFeedbackRequest request) {
		assertThatThrownBy(() -> feedbackService.create(1L, request))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
			);
	}
}
