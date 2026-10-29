package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.ai.dto.QuizQuestionPreview;
import io.edupilot.session.dto.TurnResponse;

@ExtendWith(OutputCaptureExtension.class)
class SessionStreamAccessDeliveryTest {
	@ParameterizedTest
	@EnumSource(Packet.class)
	void deniedAccessSuppressesEveryPayloadAndCancelsUpstream(Packet packet, CapturedOutput output) {
		AtomicBoolean denied = new AtomicBoolean();
		AtomicInteger cleanups = new AtomicInteger();
		RecordingEmitter emitter = new RecordingEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(1L, 100L,
			() -> { if (denied.get()) throw new IllegalStateException("synthetic-private-permission-details"); },
			cleanups::incrementAndGet, emitter);
		connection.sendReady(Instant.now());
		AiStreamCancellation upstream = new AiStreamCancellation();
		assertThat(connection.begin(upstream)).isTrue();
		denied.set(true);

		try {
			send(packet, connection);
		} catch (AiClientException interruption) {
			assertThat(interruption.retryable()).isFalse();
		}

		assertThat(emitter.deliveries.get()).isEqualTo(1); // only the earlier authorized ready event
		assertThat(connection.isClosed()).isTrue();
		assertThat(connection.closeReason()).isEqualTo(SessionStreamConnection.CloseReason.ACCESS_REVOKED);
		assertThat(upstream.isCancelled()).isTrue();
		assertThat(upstream.isUserCancelled()).isFalse();
		assertThat(cleanups.get()).isEqualTo(1);
		assertThat(output).doesNotContain("synthetic-private-permission-details");
	}

	@Test
	void idleHeartbeatDetectsRevocationWithoutWaitingForAnotherTurn() {
		AtomicBoolean denied = new AtomicBoolean();
		RecordingEmitter emitter = new RecordingEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(1L, 100L,
			() -> { if (denied.get()) throw new IllegalStateException("synthetic unavailable access read"); },
			() -> {}, emitter);
		connection.sendReady(Instant.now());
		ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
		connection.heartbeatTask(heartbeat);
		denied.set(true);

		connection.sendHeartbeatIfIdle(0);

		assertThat(connection.isClosed()).isTrue();
		assertThat(emitter.deliveries.get()).isEqualTo(1);
		verify(heartbeat).cancel(false);
	}

	private void send(Packet packet, SessionStreamConnection connection) {
		switch (packet) {
			case READY -> connection.sendReady(Instant.now());
			case STATUS -> connection.send(TurnStreamEvent.status("synthetic-stage"));
			case THOUGHT -> connection.send(TurnStreamEvent.thoughtSummary("Synthetic thought"));
			case CONTENT -> connection.send(TurnStreamEvent.contentDelta("Synthetic content"));
			case QUIZ -> connection.send(TurnStreamEvent.quizQuestion(mock(QuizQuestionPreview.class)));
			case UI_ACTION -> connection.sendUiAction(UiAction.noteProposal("Synthetic action"));
			case COMPLETED -> connection.sendCompleted("synthetic-request", mock(TurnResponse.class));
			case ERROR -> connection.sendError(new SessionStreamError("SYNTHETIC_ERROR", "INTERNAL", "Synthetic error", false, "synthetic-trace"));
			case HEARTBEAT -> connection.send(TurnStreamEvent.heartbeat());
		}
	}

	private enum Packet { READY, STATUS, THOUGHT, CONTENT, QUIZ, UI_ACTION, COMPLETED, ERROR, HEARTBEAT }
	private static final class RecordingEmitter extends SseEmitter {
		private final AtomicInteger deliveries = new AtomicInteger();
		@Override public void send(SseEventBuilder event) throws IOException { deliveries.incrementAndGet(); }
	}
}
