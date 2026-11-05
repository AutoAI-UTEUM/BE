package io.edupilot.composer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.dto.AiUsage;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.SessionStreamAccessGuard;
import io.edupilot.user.UserRole;

class ComposerPredictionServiceTest {
	final ComposerSnapshotService snapshots = mock(ComposerSnapshotService.class);
	final SessionStreamAccessGuard access = mock(SessionStreamAccessGuard.class);
	final ComposerAdmissionService admissions = mock(ComposerAdmissionService.class);
	final ComposerAiClient ai = mock(ComposerAiClient.class);
	final TestCall call = new TestCall();
	final CapturingEmitter emitter = new CapturingEmitter();
	final SessionStreamAccessGuard.Access grant = new SessionStreamAccessGuard.Access(UserRole.LEARNER, 4);
	final ComposerAiRequest request = new ComposerAiRequest("1.0", "message-50", "version", ComposerAiRequest.Activity.QA,
		List.of(new ComposerAiRequest.Message(ComposerAiRequest.Role.ASSISTANT, "Public answer")));
	final ComposerSnapshotService.Snapshot snapshot = new ComposerSnapshotService.Snapshot(1L, 10L, 50L, request);
	final AiUsage usage = new AiUsage("synthetic-model", 12L, 4L, null, 40L);
	ComposerPredictionService service;

	@BeforeEach void setup() {
		when(access.captureAccess(1L)).thenReturn(grant);
		when(snapshots.capture(1L, 10L, 50L, grant)).thenReturn(snapshot);
		when(snapshots.fresh(snapshot, grant)).thenReturn(true);
		when(admissions.reserve(1L, UserRole.LEARNER, snapshot.reservationId())).thenReturn(true);
		when(ai.start(request)).thenReturn(call);
		service = service(ComposerAdmissionServiceTest.policy());
	}
	ComposerPredictionService service(ComposerProperties properties) {
		return new ComposerPredictionService(properties, snapshots, access, admissions, ai) {
			@Override SseEmitter newEmitter() { return emitter; }
		};
	}
	@AfterEach void close() { service.close(); }
	void start() { service.open(1L, 10L, 50L); verify(ai, timeout(2000)).start(request); }
	ComposerPredictionService.Result result() throws Exception { return emitter.result.get(3, TimeUnit.SECONDS); }
	ComposerAiResponse response(String suggestion) { return new ComposerAiResponse("1.0", "message-50", "version", suggestion, List.of(), usage); }

	@Test void offOrIneligibleNeverReservesOrCallsProvider() throws Exception {
		service.close(); service = service(new ComposerProperties(false, false, 0, 0, 0, 0, 0, Duration.ofSeconds(10)));
		service.open(1L, 10L, 50L); assertThat(result().suggestion()).isNull();
		verifyNoInteractions(ai, admissions);
	}
	@Test void gatedContextIsNullableAndCannotBeMistakenForAnInternalAiResponse() throws Exception {
		when(snapshots.capture(1L, 10L, 50L, grant)).thenReturn(null);
		service.open(1L, 10L, 50L);
		var value = result();
		assertThat(value.schemaVersion()).isEqualTo("1.0");
		assertThat(value.sourceTurnId()).isEqualTo("message-50");
		assertThat(value.sourceMessageId()).isEqualTo(50L);
		assertThat(value.contextVersion()).isNull(); assertThat(value.suggestion()).isNull();
		assertThat(value.warnings()).isEmpty();
		exportWire("gated.json", value); verifyNoInteractions(ai, admissions);
	}
	@Test void authenticationOwnershipAndEligibilityDenialsRemainDenials() {
		when(access.captureAccess(1L)).thenThrow(new BusinessException(ErrorCode.GUARDIAN_AI_CONSENT_REQUIRED));
		assertThatThrownBy(() -> service.open(1L, 10L, 50L)).isInstanceOf(BusinessException.class);
		verifyNoInteractions(ai, admissions);
	}
	@Test void duplicateDoesNotCallProvider() throws Exception {
		when(admissions.reserve(any(), any(), any())).thenReturn(false);
		service.open(1L, 10L, 50L);
		assertThat(result().warnings()).extracting(ComposerAiResponse.Warning::type).containsExactly("PREDICTION_UNAVAILABLE");
		verifyNoInteractions(ai);
	}
	@Test void separateCompletionRecordsUsageAndReturnsOnlyOneSuggestion() throws Exception {
		start(); call.result.complete(response("Could you give an example?"));
		assertThat(result()).isEqualTo(new ComposerPredictionService.Result("1.0", "message-50", "version",
			"Could you give an example?", List.of(), 50L));
		verify(admissions).finish(1L, snapshot.reservationId(), usage, true);
		verify(ai, times(1)).start(request);
	}
	@Test void serializedSharedNamesFollowAiV1AndServerOnlyFieldsAreAbsent() throws Exception {
		start(); call.result.complete(response("Could you give an example?"));
		var value = result();
		var json = new tools.jackson.databind.ObjectMapper().valueToTree(value);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("schemaVersion", "sourceTurnId", "contextVersion",
			"suggestion", "warnings", "sourceMessageId");
		assertThat(json.get("sourceTurnId").asString()).isEqualTo(request.sourceTurnId());
		assertThat(json.get("contextVersion").asString()).isEqualTo(request.contextVersion());
		assertThat(json.has("reason")).isFalse(); assertThat(json.has("usage")).isFalse();
		exportWire("accepted.json", value);
	}
	@ParameterizedTest @ValueSource(strings = {"", "line1\nline2", "line1\u2028line2"})
	void invalidTextIsSuppressedButUsageSurvives(String invalid) throws Exception {
		start(); call.result.complete(response(invalid));
		assertThat(result().suggestion()).isNull();
		verify(admissions).finish(1L, snapshot.reservationId(), usage, false);
	}
	@Test void invalidEchoIsSuppressedAndSpendIsStillRecorded() throws Exception {
		start(); call.result.complete(new ComposerAiResponse("1.0", "another-source", "version", "draft", List.of(), usage));
		var value = result(); assertThat(value.suggestion()).isNull();
		assertThat(value.warnings()).extracting(ComposerAiResponse.Warning::type).containsExactly("PREDICTION_UNAVAILABLE");
		assertThat(value.warnings().getFirst().message()).doesNotContain("private-provider-warning");
		verify(admissions).finish(1L, snapshot.reservationId(), usage, false);
	}
	@Test void nullWarningKeepsKnownUsage() throws Exception {
		start(); call.result.complete(new ComposerAiResponse("1.0", "message-50", "version", null,
			List.of(new ComposerAiResponse.Warning("PREDICTION_UNAVAILABLE", "private-provider-warning")), usage));
		var value = result(); assertThat(value.suggestion()).isNull();
		assertThat(value.warnings()).extracting(ComposerAiResponse.Warning::type).containsExactly("PREDICTION_UNAVAILABLE");
		assertThat(value.warnings().getFirst().message()).doesNotContain("private-provider-warning");
		exportWire("unavailable.json", value); verify(admissions).finish(1L, snapshot.reservationId(), usage, false);
	}
	@Test void staleBeforeStartDoesNotCallUpstream() throws Exception {
		when(snapshots.fresh(snapshot, grant)).thenReturn(false);
		service.open(1L, 10L, 50L);
		assertThat(result().warnings()).extracting(ComposerAiResponse.Warning::type).containsExactly("PREDICTION_UNAVAILABLE");
		verifyNoInteractions(ai);
	}
	@Test void staleResponseIsDiscardedAfterKnownUsageRecorded() throws Exception {
		start(); doAnswer(invocation -> {
			when(snapshots.fresh(snapshot, grant)).thenReturn(false); return null;
		}).when(admissions).finish(1L, snapshot.reservationId(), usage, true);
		call.result.complete(response("draft"));
		assertThat(result().suggestion()).isNull(); verify(admissions, timeout(2000)).finish(1L, snapshot.reservationId(), usage, true);
	}
	@Test void disconnectPropagatesToHttpAndUnknownCostIsNeverZeroed() {
		start(); emitter.error.accept(new IOException("synthetic disconnect"));
		assertThat(call.cancelled).isTrue();
		verify(admissions, timeout(2000)).finish(1L, snapshot.reservationId(), null, false);
	}
	@Test void revocationDuringCallCancelsAndDoesNotDeliverSuggestion() {
		start(); when(snapshots.fresh(snapshot, grant)).thenThrow(new BusinessException(ErrorCode.GUARDIAN_CONSENT_CHANGED));
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> call.cancelled);
		verify(admissions, timeout(2000)).finish(1L, snapshot.reservationId(), null, false);
		assertThat(emitter.result.isDone()).isFalse();
		assertThat(emitter.completions).isGreaterThan(0);
	}
	@Test void slowAdmissionCannotStartHttpAfterOverallDeadline() throws Exception {
		service.close(); service = service(new ComposerProperties(true, true, 10, 3, 1, 1000, 100, Duration.ofMillis(150)));
		doAnswer(invocation -> {
			new java.util.concurrent.CountDownLatch(1).await(200, TimeUnit.MILLISECONDS); return true;
		}).when(admissions).reserve(any(), any(), any());
		service.open(1L, 10L, 50L);
		assertThat(result().suggestion()).isNull(); verifyNoInteractions(ai);
	}
	@Test void failedAdmissionNeverStartsHttp() throws Exception {
		when(admissions.reserve(any(), any(), any())).thenThrow(new IllegalStateException("synthetic storage failure"));
		service.open(1L, 10L, 50L); assertThat(result().suggestion()).isNull(); verifyNoInteractions(ai);
	}
	@Test void deadlineCancelsWithoutRetry() throws Exception {
		service.close(); service = service(new ComposerProperties(true, true, 10, 3, 1, 1000, 100, Duration.ofMillis(150)));
		start(); assertThat(result().suggestion()).isNull();
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> call.cancelled);
		verify(ai, times(1)).start(request);
	}
	@Test void upstreamFailureAndUsageWriteFailureCannotFailSavedTurn() throws Exception {
		start(); doThrow(new IllegalStateException("synthetic database failure")).when(admissions).finish(any(), any(), any(), anyBoolean());
		call.result.completeExceptionally(new IllegalStateException("private-provider-details"));
		assertThat(result().suggestion()).isNull(); verify(ai, times(1)).start(request);
	}

	static void exportWire(String name, ComposerPredictionService.Result value) throws Exception {
		var directory = java.nio.file.Path.of("build", "composer-wire");
		java.nio.file.Files.createDirectories(directory);
		java.nio.file.Files.writeString(directory.resolve(name), new tools.jackson.databind.ObjectMapper().writeValueAsString(value));
	}

	static class TestCall implements ComposerAiClient.Call {
		final CompletableFuture<ComposerAiResponse> result = new CompletableFuture<>();
		volatile boolean cancelled;
		public CompletableFuture<ComposerAiResponse> result() { return result; }
		public void cancel() { cancelled = true; result.cancel(true); }
	}
	static class CapturingEmitter extends SseEmitter {
		final CompletableFuture<ComposerPredictionService.Result> result = new CompletableFuture<>();
		Consumer<Throwable> error;
		volatile int completions;
		CapturingEmitter() { super(0L); }
		@Override public synchronized void send(SseEventBuilder builder) {
			builder.build().forEach(data -> {
				if (data.getData() instanceof ComposerPredictionService.Result value) result.complete(value);
			});
		}
		@Override public void onError(Consumer<Throwable> callback) { error = callback; }
		@Override public synchronized void complete() { completions++; super.complete(); }
	}
}
