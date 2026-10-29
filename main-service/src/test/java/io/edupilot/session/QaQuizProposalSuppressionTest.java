package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class QaQuizProposalSuppressionTest {

	@Test
	void twoDeclinesSuppressFiveCompletedTurnsAndThenAllowAgain() {
		QaQuizProposalSuppression suppression = new QaQuizProposalSuppression(
			new QaQuizProposalProperties(true, 2, 5)
		);

		suppression.declined(100L);
		assertThat(suppression.isSuppressed(100L)).isFalse();
		suppression.declined(100L);
		assertThat(suppression.isSuppressed(100L)).isTrue();
		assertThat(suppression.isSuppressed(200L)).isFalse();
		for (int turn = 0; turn < 4; turn++) {
			suppression.turnCompleted(100L, TurnEventType.USER_QUESTION);
			assertThat(suppression.isSuppressed(100L)).isTrue();
		}
		suppression.turnCompleted(100L, TurnEventType.USER_QUESTION);
		assertThat(suppression.isSuppressed(100L)).isFalse();
	}

	@Test
	void successfulQuizTypeSelectionClearsDeclineCountAndSuppression() {
		QaQuizProposalSuppression suppression = new QaQuizProposalSuppression(
			new QaQuizProposalProperties(true, 2, 5)
		);

		suppression.declined(100L);
		suppression.turnCompleted(100L, TurnEventType.QUIZ_TYPE_SELECTED);
		suppression.declined(100L);
		assertThat(suppression.isSuppressed(100L)).isFalse();
		suppression.declined(100L);
		assertThat(suppression.isSuppressed(100L)).isTrue();
		suppression.turnCompleted(100L, TurnEventType.QUIZ_TYPE_SELECTED);
		assertThat(suppression.isSuppressed(100L)).isFalse();
	}

	@Test
	void transactionalDeclinesAreAppliedOnlyAfterCommit() {
		QaQuizProposalSuppression suppression = new QaQuizProposalSuppression(
			new QaQuizProposalProperties(true, 2, 5)
		);
		TransactionSynchronizationManager.initSynchronization();
		try {
			suppression.declinedAfterCommit(100L);
			suppression.declinedAfterCommit(100L);
			assertThat(suppression.isSuppressed(100L)).isFalse();
			for (TransactionSynchronization synchronization :
				TransactionSynchronizationManager.getSynchronizations()) {
				synchronization.afterCommit();
			}
			assertThat(suppression.isSuppressed(100L)).isTrue();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}
}
