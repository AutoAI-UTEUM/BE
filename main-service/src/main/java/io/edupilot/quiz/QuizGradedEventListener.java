package io.edupilot.quiz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class QuizGradedEventListener {

	private static final Logger log =
		LoggerFactory.getLogger(QuizGradedEventListener.class);

	private final QuizPostGradingHook hook;

	QuizGradedEventListener(QuizPostGradingHook hook) {
		this.hook = hook;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onGraded(QuizGradedEvent event) {
		try {
			hook.onGraded(event.submission());
		} catch (RuntimeException exception) {
			log.warn(
				"Quiz post-grading hook failed: submissionId={}, quizId={}",
				event.submission().submissionId(),
				event.submission().quizId()
			);
		}
	}
}
