package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.AiClientException;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialAccessService;

class SessionStreamServiceTest {

	private final LearningSessionRepository repository =
		mock(LearningSessionRepository.class);
	private final MaterialAccessService materialAccess = mock(MaterialAccessService.class);
	private final SessionStreamService service =
		new SessionStreamService(repository, materialAccess);

	@AfterEach
	void tearDown() {
		service.shutdown();
	}

	@Test
	void validatesOwnershipAndActiveStatus() {
		when(repository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.empty());
		assertError(
			() -> service.connect(1L, 100L),
			ErrorCode.SESSION_NOT_FOUND
		);

		LearningSession completed = mock(LearningSession.class);
		when(completed.getStatus()).thenReturn(SessionStatus.COMPLETED);
		when(repository.findByIdAndUser_Id(101L, 1L))
			.thenReturn(Optional.of(completed));
		assertError(
			() -> service.connect(1L, 101L),
			ErrorCode.SESSION_NOT_ACTIVE
		);
	}

	@Test
	@SuppressWarnings("unchecked")
	void connectImmediatelyEmitsReadyBeforeHeartbeat() {
		LearningSession active = mock(LearningSession.class);
		when(active.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(repository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(active));

		SseEmitter emitter = service.connect(1L, 100L);
		Collection<ResponseBodyEmitter.DataWithMediaType> earlyEvents =
			(Collection<ResponseBodyEmitter.DataWithMediaType>)
				ReflectionTestUtils.getField(
					emitter,
					"earlySendAttempts"
				);
		assertThat(earlyEvents).isNotNull();
		var eventParts = earlyEvents.stream()
			.map(ResponseBodyEmitter.DataWithMediaType::getData)
			.toList();

		assertThat(eventParts.stream()
			.filter(String.class::isInstance)
			.map(String.class::cast))
			.anyMatch(value -> value.contains("event:ready"))
			.noneMatch(value -> value.contains(":heartbeat"));
		Map<?, ?> ready = eventParts.stream()
			.filter(Map.class::isInstance)
			.map(Map.class::cast)
			.findFirst()
			.orElseThrow();
		assertThat(ready.get("sessionId")).isEqualTo(100L);
		String connectedAt = (String)ready.get("connectedAt");
		assertThat(connectedAt).endsWith("Z");
		assertThat(Instant.parse(connectedAt)).isBeforeOrEqualTo(Instant.now());
	}

	@Test
	void replacesIdleConnectionButRejectsConcurrentRunningConnection() {
		LearningSession active = mock(LearningSession.class);
		when(active.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(repository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(active));

		var first = service.connect(1L, 100L);
		var second = service.connect(1L, 100L);
		assertThat(second).isNotSameAs(first);

		AiStreamCancellation cancellation = new AiStreamCancellation();
		assertThat(service.beginTurn(1L, 100L, "request-1", cancellation)).isPresent();
		assertError(
			() -> service.connect(1L, 100L),
			ErrorCode.TURN_IN_PROGRESS
		);
	}

	@Test
	@SuppressWarnings("unchecked")
	void turnStartedBeforeReplacementReservationCannotBeReplaced() throws Exception {
		LearningSession active = mock(LearningSession.class);
		when(active.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(repository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(active));
		service.connect(1L, 100L);
		Map<Long, SessionStreamConnection> connections =
			(Map<Long, SessionStreamConnection>)ReflectionTestUtils
				.getField(service, "connections");
		SessionStreamConnection first = connections.get(100L);
		CountDownLatch replacementRequested = new CountDownLatch(1);
		CountDownLatch turnStarted = new CountDownLatch(1);
		Map<Long, SessionStreamConnection> gated = new ConcurrentHashMap<>(connections) {
			@Override
			public SessionStreamConnection compute(
				Long id,
				BiFunction<? super Long, ? super SessionStreamConnection,
					? extends SessionStreamConnection> action
			) {
				replacementRequested.countDown();
				await(turnStarted);
				return super.compute(id, action);
			}
		};
		ReflectionTestUtils.setField(service, "connections", gated);
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			Future<?> replacement = executor.submit(() -> {
				assertError(
					() -> service.connect(1L, 100L),
					ErrorCode.TURN_IN_PROGRESS
				);
			});
			await(replacementRequested);
			AiStreamCancellation cancellation = new AiStreamCancellation();
			assertThat(service.beginTurn(1L, 100L, "request-1", cancellation))
				.contains(first);
			turnStarted.countDown();
			replacement.get(3, TimeUnit.SECONDS);
			assertThat(first.isClosed()).isFalse();
			assertThat(cancellation.isCancelled()).isFalse();
		}
	}

	@Test
	void replacementReadyWinsAndOldCallbacksCannotRemoveOrCancelNewTurn() {
		stubActiveSession(100L);
		ControllableSseEmitter firstEmitter = new ControllableSseEmitter();
		ControllableSseEmitter secondEmitter = new ControllableSseEmitter();
		AtomicInteger creations = new AtomicInteger();
		SessionStreamService controlled = new SessionStreamService(
			repository, materialAccess, () -> creations.getAndIncrement() == 0 ? firstEmitter : secondEmitter
		);
		try {
			controlled.connect(1L, 100L);
			SessionStreamConnection first = connection(controlled, 100L);
			ScheduledFuture<?> firstHeartbeat = (ScheduledFuture<?>)ReflectionTestUtils
				.getField(first, "heartbeatTask");
			controlled.connect(1L, 100L);
			SessionStreamConnection second = connection(controlled, 100L);
			AiStreamCancellation cancellation = new AiStreamCancellation();
			assertThat(controlled.beginTurn(1L, 100L, "new-request", cancellation))
				.contains(second);

			firstEmitter.completion.run();
			firstEmitter.timeout.run();
			firstEmitter.error.accept(new java.io.IOException("late callback"));
			first.sendHeartbeatIfIdle(0);
			assertThat(connection(controlled, 100L)).isSameAs(second);
			assertThat(second.isRunning()).isTrue();
			assertThat(cancellation.isCancelled()).isFalse();
			assertThat(firstHeartbeat.isCancelled()).isTrue();
			assertThat(first.closeReason())
				.isEqualTo(SessionStreamConnection.CloseReason.IDLE_REPLACED);
		} finally {
			controlled.shutdown();
		}
	}

	@Test
	void delayedOldCleanupAfterNewTurnUsesExpectedConnectionRemoval() throws Exception {
		stubActiveSession(100L);
		ControllableSseEmitter firstEmitter = new ControllableSseEmitter();
		AtomicInteger creations = new AtomicInteger();
		SessionStreamService controlled = new SessionStreamService(repository, materialAccess,
			() -> creations.getAndIncrement() == 0 ? firstEmitter : new ControllableSseEmitter());
		CountDownLatch cleanupEntered = new CountDownLatch(1);
		CountDownLatch newTurnStarted = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			controlled.connect(1L, 100L);
			SessionStreamConnection first = connection(controlled, 100L);
			AiStreamCancellation oldCancellation = new AiStreamCancellation();
			controlled.beginTurn(1L, 100L, "old-request", oldCancellation);
			Map<Long, SessionStreamConnection> gated = new ConcurrentHashMap<>(Map.of(100L, first)) {
				@Override
				public boolean remove(Object key, Object expected) {
					if (expected == first) {
						cleanupEntered.countDown();
						await(newTurnStarted);
					}
					return super.remove(key, expected);
				}
			};
			ReflectionTestUtils.setField(controlled, "connections", gated);
			Future<?> cleanup = executor.submit(firstEmitter.timeout);
			await(cleanupEntered);
			controlled.connect(1L, 100L);
			SessionStreamConnection second = connection(controlled, 100L);
			AiStreamCancellation currentCancellation = new AiStreamCancellation();
			assertThat(controlled.beginTurn(1L, 100L, "new-request", currentCancellation))
				.contains(second);
			newTurnStarted.countDown();
			cleanup.get(3, TimeUnit.SECONDS);
			assertThat(connection(controlled, 100L)).isSameAs(second);
			assertThat(oldCancellation.isCancelled()).isTrue();
			assertThat(currentCancellation.isCancelled()).isFalse();
		} finally {
			newTurnStarted.countDown();
			controlled.shutdown();
		}
	}

	@Test
	void overlappingConnectionsSelectOnlyReadyWinnerWithoutBlockingOtherSessions()
		throws Exception {
		stubActiveSession(100L);
		stubActiveSession(200L);
		ControllableSseEmitter preparing = new ControllableSseEmitter();
		preparing.blockedEvent = "ready";
		AtomicInteger creations = new AtomicInteger();
		SessionStreamService controlled = new SessionStreamService(repository, materialAccess,
			() -> creations.getAndIncrement() == 0 ? preparing : new ControllableSseEmitter());
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> pending = executor.submit(() -> assertThatThrownBy(
				() -> controlled.connect(1L, 100L)).isInstanceOf(AiClientException.class));
			await(preparing.sendEntered);
			SessionStreamConnection replaced = connection(controlled, 100L);
			ScheduledFuture<?> heartbeat = (ScheduledFuture<?>)ReflectionTestUtils
				.getField(replaced, "heartbeatTask");
			assertThat(controlled.beginTurn(1L, 100L, "too-early", new AiStreamCancellation()))
				.isEmpty();
			// A slow ready send holds neither the Map nor another session's state lock.
			executor.submit(() -> controlled.connect(1L, 200L)).get(3, TimeUnit.SECONDS);
			controlled.connect(1L, 100L);
			SessionStreamConnection winner = connection(controlled, 100L);
			AiStreamCancellation cancellation = new AiStreamCancellation();
			assertThat(controlled.beginTurn(1L, 100L, "winner", cancellation))
				.contains(winner);
			preparing.releaseSend.countDown();
			pending.get(3, TimeUnit.SECONDS);
			assertThat(connection(controlled, 100L)).isSameAs(winner);
			assertThat(cancellation.isCancelled()).isFalse();
			assertThat(heartbeat.isCancelled()).isTrue();
		} finally {
			preparing.releaseSend.countDown();
			controlled.shutdown();
		}
	}

	@Test
	void failedReadyIsRemovedAndRecoveryDoesNotInheritItsCancellation() {
		stubActiveSession(100L);
		ControllableSseEmitter failing = new ControllableSseEmitter();
		failing.failingEvent = "ready";
		AtomicInteger creations = new AtomicInteger();
		SessionStreamService controlled = new SessionStreamService(repository, materialAccess,
			() -> creations.getAndIncrement() == 0 ? failing : new ControllableSseEmitter());
		try {
			assertThatThrownBy(() -> controlled.connect(1L, 100L))
				.isInstanceOf(AiClientException.class);
			assertThat(connection(controlled, 100L)).isNull();
			assertThat(controlled.beginTurn(1L, 100L, "failed-ready", new AiStreamCancellation()))
				.isEmpty();
			controlled.connect(1L, 100L);
			AiStreamCancellation cancellation = new AiStreamCancellation();
			assertThat(controlled.beginTurn(1L, 100L, "recovered", cancellation)).isPresent();
			assertThat(cancellation.isCancelled()).isFalse();
			failing.completion.run();
			assertThat(connection(controlled, 100L).isRunning()).isTrue();
			assertThat(cancellation.isCancelled()).isFalse();
		} finally {
			controlled.shutdown();
		}
	}

	@Test
	void absentOrWrongOwnerConnectionKeepsJsonFallback() {
		assertThat(service.beginTurn(1L, 100L, "absent", new AiStreamCancellation()))
			.isEmpty();
		stubActiveSession(100L);
		service.connect(1L, 100L);
		assertThat(service.beginTurn(2L, 100L, "wrong-owner", new AiStreamCancellation()))
			.isEmpty();
		assertThat(connection(service, 100L).isRunning()).isFalse();
	}

	@Test
	void cancelsOnlyOwnedRunningTurnAndMapsErrorAsNonRetryable() {
		LearningSession active = mock(LearningSession.class);
		when(active.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(repository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(active));
		service.connect(1L, 100L);
		AiStreamCancellation cancellation = new AiStreamCancellation();

		assertThat(service.cancelTurn(1L, 100L)).isFalse();
		assertThat(service.beginTurn(1L, 100L, "request-1", cancellation)).isPresent();
		assertThat(service.cancelTurn(2L, 100L)).isFalse();
		assertThat(service.cancelTurn(1L, 999L)).isFalse();
		assertThat(service.cancelTurn(1L, 100L)).isTrue();
		assertThat(cancellation.isUserCancelled()).isTrue();

		SessionStreamConnection connection = mock(
			SessionStreamConnection.class
		);
		service.fail(
			connection,
			new BusinessException(ErrorCode.TURN_CANCELLED)
		);
		var error = org.mockito.ArgumentCaptor.forClass(
			SessionStreamError.class
		);
		verify(connection).sendError(error.capture());
		assertThat(error.getValue().code()).isEqualTo("TURN_CANCELLED");
		assertThat(error.getValue().retryable()).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void cleanupAndConcurrentConnectCompleteWithoutDeadlock() {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			LearningSession active = mock(LearningSession.class);
			when(active.getStatus()).thenReturn(SessionStatus.ACTIVE);
			CountDownLatch connectionLockHeld = new CountDownLatch(1);
			CountDownLatch concurrentConnectValidated = new CountDownLatch(1);
			AtomicInteger lookups = new AtomicInteger();
			when(repository.findByIdAndUser_Id(100L, 1L))
				.thenAnswer(invocation -> {
					if (lookups.incrementAndGet() > 1) {
						concurrentConnectValidated.countDown();
					}
					return Optional.of(active);
				});

			service.connect(1L, 100L);
			Map<Long, SessionStreamConnection> connections =
				(Map<Long, SessionStreamConnection>)ReflectionTestUtils
					.getField(service, "connections");
			SessionStreamConnection connection = connections.get(100L);
			ExecutorService executor = Executors.newFixedThreadPool(
				2,
				Thread.ofPlatform()
					.daemon()
					.name("session-stream-deadlock-test-", 0)
					.factory()
			);
			try {
				Future<?> cleanup = executor.submit(() -> {
					synchronized (connection) {
						connectionLockHeld.countDown();
						await(concurrentConnectValidated);
						connection.replaceIdle();
					}
				});
				Future<?> connect = executor.submit(() -> {
					await(connectionLockHeld);
					service.connect(1L, 100L);
				});

				cleanup.get(3, TimeUnit.SECONDS);
				connect.get(3, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
			}
		});
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(3, TimeUnit.SECONDS)) {
				throw new AssertionError("Timed out waiting for test latch");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private void stubActiveSession(Long sessionId) {
		LearningSession active = mock(LearningSession.class);
		when(active.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(repository.findByIdAndUser_Id(sessionId, 1L))
			.thenReturn(Optional.of(active));
	}

	@SuppressWarnings("unchecked")
	private SessionStreamConnection connection(SessionStreamService owner, Long sessionId) {
		return ((Map<Long, SessionStreamConnection>)ReflectionTestUtils
			.getField(owner, "connections")).get(sessionId);
	}

	private void assertError(Runnable action, ErrorCode code) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(code)
			);
	}
}
