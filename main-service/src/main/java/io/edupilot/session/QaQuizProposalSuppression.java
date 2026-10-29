package io.edupilot.session;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Component
public class QaQuizProposalSuppression {

	private final Cache<Long, State> states = Caffeine.newBuilder()
		.maximumSize(100_000)
		.expireAfterAccess(Duration.ofDays(1))
		.build();
	private final int rejectionThreshold;
	private final int suppressionTurns;

	public QaQuizProposalSuppression(QaQuizProposalProperties properties) {
		this.rejectionThreshold = properties.rejectionThreshold();
		this.suppressionTurns = properties.suppressionTurns();
	}

	public boolean isSuppressed(Long sessionId) {
		State state = states.getIfPresent(sessionId);
		return state != null && state.remainingTurns() > 0;
	}

	public void declined(Long sessionId) {
		states.asMap().compute(sessionId, (ignored, state) -> {
			if (state != null && state.remainingTurns() > 0) {
				return state;
			}
			int refusals = state == null ? 1 : state.refusals() + 1;
			return refusals >= rejectionThreshold
				? new State(0, suppressionTurns)
				: new State(refusals, 0);
		});
	}

	public void declinedAfterCommit(Long sessionId) {
		afterCommit(() -> declined(sessionId));
	}

	public void turnCompleted(Long sessionId, TurnEventType eventType) {
		if (eventType == TurnEventType.QUIZ_TYPE_SELECTED) {
			states.invalidate(sessionId);
			return;
		}
		states.asMap().computeIfPresent(sessionId, (ignored, state) ->
			state.remainingTurns() > 0
				? new State(state.refusals(), state.remainingTurns() - 1)
				: state
		);
	}

	public void turnCompletedAfterCommit(
		Long sessionId,
		TurnEventType eventType
	) {
		afterCommit(() -> turnCompleted(sessionId, eventType));
	}

	private void afterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(
				new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						action.run();
					}
				}
			);
		} else {
			action.run();
		}
	}

	private record State(int refusals, int remainingTurns) {
	}
}
