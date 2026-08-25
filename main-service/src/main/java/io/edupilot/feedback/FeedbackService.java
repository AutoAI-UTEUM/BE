package io.edupilot.feedback;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.feedback.dto.CreateFeedbackRequest;
import io.edupilot.feedback.dto.FeedbackResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
public class FeedbackService {

	private static final int MESSAGE_MAX_LENGTH = 2000;

	private final FeedbackRepository feedbackRepository;
	private final UserRepository userRepository;

	public FeedbackService(
		FeedbackRepository feedbackRepository,
		UserRepository userRepository
	) {
		this.feedbackRepository = feedbackRepository;
		this.userRepository = userRepository;
	}

	@Transactional
	public FeedbackResponse create(Long userId, CreateFeedbackRequest request) {
		validate(request);
		User user = userRepository.getReferenceById(userId);
		Feedback feedback = feedbackRepository.saveAndFlush(Feedback.create(
			user,
			request.category(),
			request.message(),
			request.pageUrl(),
			request.clientVersion()
		));
		return FeedbackResponse.from(feedback);
	}

	private void validate(CreateFeedbackRequest request) {
		if (
			request.category() == null
				|| request.message() == null
				|| request.message().isBlank()
				|| request.message().length() > MESSAGE_MAX_LENGTH
		) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}
}
