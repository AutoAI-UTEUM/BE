package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

class SessionStreamServiceTest {

	private final LearningSessionRepository repository =
		mock(LearningSessionRepository.class);
	private final SessionStreamService service =
		new SessionStreamService(repository);

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
	void replacesIdleConnectionButRejectsConcurrentRunningConnection() {
		LearningSession active = mock(LearningSession.class);
		when(active.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(repository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(active));

		var first = service.connect(1L, 100L);
		var second = service.connect(1L, 100L);
		assertThat(second).isNotSameAs(first);

		AiStreamCancellation cancellation = new AiStreamCancellation();
		assertThat(service.beginTurn(1L, 100L, cancellation)).isPresent();
		assertError(
			() -> service.connect(1L, 100L),
			ErrorCode.TURN_IN_PROGRESS
		);
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

	private void assertError(Runnable action, ErrorCode code) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(code)
			);
	}
}
