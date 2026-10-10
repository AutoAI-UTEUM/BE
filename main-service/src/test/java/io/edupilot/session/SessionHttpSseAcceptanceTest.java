package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.MainServiceApplication;
import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.classroom.ClassroomWeekMaterial;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.classroom.ClassroomWeekStatus;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.session.httpacceptance.HttpSseAcceptanceWire;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

/**
 * Actual loopback Tomcat HTTP/JWT/JPA/turn delivery; only the external AI is controlled.
 * The socket witness never creates an emitter or invokes servlet/emitter callbacks.
 * Adult accounts are new synthetic signups with a recorded birthdate and verified email.
 */
@SpringBootTest(classes = MainServiceApplication.class,
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"server.address=127.0.0.1",
		"spring.datasource.url=jdbc:h2:mem:http-sse-acceptance;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://127.0.0.1:1",
		"edupilot.ai.internal-token=synthetic-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.mail.enabled=false", "edupilot.mail.provider=logging"
	})
@ActiveProfiles("jpa-context")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
class SessionHttpSseAcceptanceTest {
	private static final Path STORAGE = Path.of("build", "test-storage",
		"http-sse-" + UUID.randomUUID()).toAbsolutePath().normalize();

	@DynamicPropertySource
	static void ownedStorage(DynamicPropertyRegistry settings) {
		settings.add("edupilot.storage.root-directory", () -> STORAGE.toString());
	}

	@LocalServerPort private int port;
	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ClassroomWeekRepository weeks;
	@Autowired private ClassroomWeekMaterialRepository links;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private MaterialPageRepository pages;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private SessionStreamService streams;
	@MockitoBean private AiClient ai;

	private final List<HttpSseAcceptanceWire> wires = new ArrayList<>();
	private final List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
	private final Map<Long, ControlledTurn> plans = new ConcurrentHashMap<>();
	private HttpClient http;
	private User learner;
	private World a;
	private World b;

	@BeforeEach
	void committedAdultPairAndControlledExternalAi() {
		// This JDBC-only production table is not an entity managed by Hibernate create-drop.
		jdbc.execute("create table if not exists session_page_records ("
			+ "id bigint auto_increment primary key, session_id bigint not null, "
			+ "page_number int not null, explained_at timestamp, created_at timestamp not null, "
			+ "updated_at timestamp not null, unique(session_id,page_number))");
		learner = actor(UserRole.LEARNER);
		a = world("A");
		b = world("B");
		http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(3)).build();
		when(ai.executeTurnStream(any(), any(), any(), any())).thenAnswer(invocation -> {
			io.edupilot.ai.dto.TurnRequest request = invocation.getArgument(0);
			long sessionId = ((Number) request.session().get("sessionId")).longValue();
			ControlledTurn plan = plans.get(sessionId);
			if (plan == null) throw new IllegalStateException("Unplanned synthetic AI call");
			return plan.execute(request, invocation.getArgument(1), invocation.getArgument(2));
		});
	}

	@AfterEach
	void closeOnlyOwnedLocalResources() throws Exception {
		for (ControlledTurn plan : plans.values()) {
			plan.next.countDown();
			plan.finish.countDown();
		}
		for (HttpSseAcceptanceWire wire : wires) wire.close();
		for (CompletableFuture<HttpResponse<String>> request : requests) {
			try { request.get(5, TimeUnit.SECONDS); }
			catch (Exception ignored) { request.cancel(true); }
		}
		if (http != null) {
			http.shutdownNow();
			assertThat(http.awaitTermination(Duration.ofSeconds(5))).isTrue();
		}
		for (HttpSseAcceptanceWire wire : wires) assertThat(wire.awaitReaderStopped()).isTrue();
	}

	@Test
	void realHttpStreamRejectsAnonymousForeignOwnerAndRemovedMembership() throws Exception {
		assertThat(get(null, streamPath(a)).statusCode()).isEqualTo(401);
		User foreign = actor(UserRole.LEARNER);
		HttpResponse<String> denied = get(foreign, streamPath(a));
		assertThat(denied.statusCode()).isEqualTo(404);
		assertThat(denied.body()).contains("SESSION_NOT_FOUND").doesNotContain(a.marker(), b.marker());
		members.deleteById(a.member().getId());
		assertThat(members.existsById(a.member().getId())).isFalse();
		denied = get(learner, streamPath(a));
		assertThat(denied.statusCode()).isEqualTo(404);
		assertThat(denied.body()).contains("MATERIAL_NOT_FOUND").doesNotContain(a.marker(), b.marker());
		assertThat(plans).isEmpty();
		System.out.println("HTTP_SSE_SECURITY_RECEIPT anonymous=401 foreign=404 revoked=404 port=" + port);
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void committedResourceRevocationStopsOnlyAOverActualHttpWhileBCompletes(Revocation change)
		throws Exception {
		HttpSseAcceptanceWire first = open(a);
		HttpSseAcceptanceWire second = open(b);
		ControlledTurn firstPlan = plan(a, false);
		ControlledTurn secondPlan = plan(b, false);
		CompletableFuture<HttpResponse<String>> firstRequest = turn(a);
		CompletableFuture<HttpResponse<String>> secondRequest = turn(b);
		first.awaitEvent("content_delta", a.marker() + "-before");
		second.awaitEvent("content_delta", b.marker() + "-before");
		SessionStreamConnection firstConnection = connection(a);
		SessionStreamConnection secondConnection = connection(b);

		if (change == Revocation.MEMBERSHIP_REMOVED) {
			HttpResponse<String> removed = delete(a.instructor(),
				"/api/classrooms/" + a.classroom().getId() + "/students/" + learner.getId());
			assertThat(removed.statusCode()).isEqualTo(200);
			assertThat(members.existsById(a.member().getId())).isFalse();
		} else {
			links.deleteById(a.link().getId());
			assertThat(links.existsById(a.link().getId())).isFalse();
		}
		firstPlan.next.countDown();
		HttpResponse<String> rejected = firstRequest.get(15, TimeUnit.SECONDS);
		assertThat(rejected.statusCode()).isEqualTo(404);
		assertThat(rejected.body()).contains("MATERIAL_NOT_FOUND");
		assertThat(first.awaitEnd()).isTrue();
		assertThat(first.eventNames()).containsExactly("ready", "content_delta");
		assertThat(first.allData()).doesNotContain(a.marker() + "-after");
		assertThat(firstConnection.closeReason()).isEqualTo(SessionStreamConnection.CloseReason.ACCESS_REVOKED);
		assertCancelledOnlyA(firstPlan, secondPlan, secondConnection);
		assertNoStoredAiAndReleasedClaim(a, "FAILED");
		assertThat(registered(a)).isFalse();

		secondPlan.next.countDown();
		second.awaitEvent("content_delta", b.marker() + "-after");
		assertThat(secondConnection.isClosed()).isFalse();
		assertThat(secondPlan.cancellation.isCancelled()).isFalse();
		secondPlan.finish.countDown();
		HttpResponse<String> accepted = secondRequest.get(15, TimeUnit.SECONDS);
		assertThat(accepted.statusCode()).isEqualTo(200);
		second.awaitEvent("completed", requestId(b));
		assertThat(second.awaitEnd()).isTrue();
		assertThat(secondConnection.closeReason()).isEqualTo(SessionStreamConnection.CloseReason.COMPLETED);
		assertThat(secondPlan.cancellation.isCancelled()).isFalse();
		assertThat(aiRows(b)).isEqualTo(1);
		assertThat(plans.values()).allSatisfy(plan -> assertThat(plan.calls.get()).isEqualTo(1));
		receipt(change.name(), first, second, firstConnection, firstPlan, secondPlan,
			rejected.statusCode(), accepted.statusCode());
	}

	@Test
	void realTcpResetCancelsOnlyDisconnectedAThroughServletSendFailure() throws Exception {
		HttpSseAcceptanceWire first = open(a);
		HttpSseAcceptanceWire second = open(b);
		ControlledTurn firstPlan = plan(a, true);
		ControlledTurn secondPlan = plan(b, false);
		CompletableFuture<HttpResponse<String>> firstRequest = turn(a);
		CompletableFuture<HttpResponse<String>> secondRequest = turn(b);
		first.awaitEvent("content_delta", a.marker() + "-before");
		second.awaitEvent("content_delta", b.marker() + "-before");
		SessionStreamConnection firstConnection = connection(a);
		SessionStreamConnection secondConnection = connection(b);

		// An actual TCP RST, not emitter.complete(), cancelTurn(), or a callback invocation.
		first.resetPeer();
		assertThat(first.awaitReaderStopped()).isTrue();
		firstPlan.next.countDown();
		HttpResponse<String> interrupted = firstRequest.get(15, TimeUnit.SECONDS);
		assertThat(interrupted.statusCode()).isEqualTo(ErrorCode.AI_STREAM_INTERRUPTED.status().value());
		assertThat(interrupted.body()).contains("AI_STREAM_INTERRUPTED");
		assertThat(firstPlan.sendAttempts.get()).isGreaterThan(0).isLessThanOrEqualTo(64);
		assertThat(firstConnection.closeReason()).isIn(
			SessionStreamConnection.CloseReason.EMITTER_ERROR,
			SessionStreamConnection.CloseReason.CONTENT_SEND_FAILED);
		assertCancelledOnlyA(firstPlan, secondPlan, secondConnection);
		assertNoStoredAiAndReleasedClaim(a, "FAILED");
		assertThat(registered(a)).isFalse();

		secondPlan.next.countDown();
		second.awaitEvent("content_delta", b.marker() + "-after");
		assertThat(secondPlan.cancellation.isCancelled()).isFalse();
		secondPlan.finish.countDown();
		HttpResponse<String> accepted = secondRequest.get(15, TimeUnit.SECONDS);
		assertThat(accepted.statusCode()).isEqualTo(200);
		second.awaitEvent("completed", requestId(b));
		assertThat(second.awaitEnd()).isTrue();
		assertThat(secondPlan.cancellation.isCancelled()).isFalse();
		assertThat(aiRows(b)).isEqualTo(1);
		assertThat(plans.values()).allSatisfy(plan -> assertThat(plan.calls.get()).isEqualTo(1));
		receipt("TCP_RESET", first, second, firstConnection, firstPlan, secondPlan,
			interrupted.statusCode(), accepted.statusCode());
	}

	private HttpSseAcceptanceWire open(World world) throws Exception {
		HttpSseAcceptanceWire wire = HttpSseAcceptanceWire.open(port, streamPath(world),
			tokens.createAccessToken(learner));
		wires.add(wire);
		wire.awaitEvent("ready", "\"sessionId\":" + world.session().getId());
		assertThat(wire.contentType()).startsWith("text/event-stream");
		return wire;
	}

	private ControlledTurn plan(World world, boolean sendAfterReset) {
		ControlledTurn plan = new ControlledTurn(world.marker(), sendAfterReset);
		plans.put(world.session().getId(), plan);
		return plan;
	}

	private CompletableFuture<HttpResponse<String>> turn(World world) {
		String body = "{\"requestId\":\"" + requestId(world)
			+ "\",\"eventType\":\"EXPLAIN_CURRENT_PAGE\",\"payload\":{}}";
		CompletableFuture<HttpResponse<String>> request = http.sendAsync(
			request(learner, "/api/sessions/" + world.session().getId() + "/turns")
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
			HttpResponse.BodyHandlers.ofString());
		requests.add(request);
		return request;
	}

	private HttpRequest.Builder request(User actor, String path) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(30));
		if (actor != null) request.header("Authorization", "Bearer " + tokens.createAccessToken(actor));
		return request;
	}

	private HttpResponse<String> get(User actor, String path) throws Exception {
		return http.send(request(actor, path).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> delete(User actor, String path) throws Exception {
		return http.send(request(actor, path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
	}

	private String streamPath(World world) { return "/api/sessions/" + world.session().getId() + "/stream"; }
	private String requestId(World world) { return "synthetic-http-" + world.session().getId(); }

	private SessionStreamConnection connection(World world) {
		Map<?, ?> connections = (Map<?, ?>) ReflectionTestUtils.getField(streams, "connections");
		return (SessionStreamConnection) connections.get(world.session().getId());
	}

	private boolean registered(World world) { return connection(world) != null; }

	private void assertCancelledOnlyA(ControlledTurn first, ControlledTurn second, SessionStreamConnection other) {
		assertThat(first.cancellation.isCancelled()).isTrue();
		assertThat(first.cancellation.isUserCancelled()).isFalse();
		assertThat(second.cancellation.isCancelled()).isFalse();
		assertThat(other.isClosed()).isFalse();
	}

	private long aiRows(World world) {
		return jdbc.queryForObject("select count(*) from chat_messages where session_id=? and sender_type='AI'",
			Long.class, world.session().getId());
	}

	private void assertNoStoredAiAndReleasedClaim(World world, String status) {
		assertThat(aiRows(world)).isZero();
		assertThat(jdbc.queryForObject("select status from chat_messages where session_id=? and request_id=?",
			String.class, world.session().getId(), requestId(world))).isEqualTo(status);
		assertThat(sessions.findById(world.session().getId()).orElseThrow().getActiveTurnRequestId()).isNull();
	}

	private User actor(UserRole role) {
		User actor = User.create("synthetic-http-" + UUID.randomUUID() + "@example.test",
			"!synthetic", "Synthetic HTTP actor", role);
		actor.recordSignupDateOfBirth(LocalDate.of(1990, 1, 1));
		actor.verifyEmail(Instant.parse("2020-01-01T00:00:00Z"));
		return users.saveAndFlush(actor);
	}

	private World world(String label) {
		User instructor = actor(UserRole.INSTRUCTOR);
		String marker = "Synthetic-HTTP-" + label + "-" + UUID.randomUUID();
		Classroom classroom = classrooms.saveAndFlush(Classroom.create(instructor, marker,
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15), ClassroomColor.BLUE, null,
			UUID.randomUUID().toString().substring(0, 10)));
		ClassroomMember member = members.saveAndFlush(ClassroomMember.create(classroom, learner, Instant.now()));
		ClassroomWeek week = weeks.saveAndFlush(ClassroomWeek.create(classroom, 1, marker + " week",
			null, ClassroomWeekStatus.PUBLISHED, 1));
		LearningMaterial material = LearningMaterial.create(instructor, marker,
			"materials/synthetic-http-" + UUID.randomUUID() + ".pdf");
		material.markReady(1);
		material = materials.saveAndFlush(material);
		pages.saveAndFlush(MaterialPage.create(material, 1, marker + " page"));
		ClassroomWeekMaterial link = links.saveAndFlush(ClassroomWeekMaterial.create(week, material, Instant.now()));
		LearningSession session = sessions.saveAndFlush(LearningSession.create(learner, material));
		return new World(instructor, classroom, member, link, session, marker);
	}

	private void receipt(String scenario, HttpSseAcceptanceWire first, HttpSseAcceptanceWire second,
		SessionStreamConnection connection, ControlledTurn firstPlan, ControlledTurn secondPlan,
		int firstStatus, int secondStatus) {
		System.out.println("HTTP_SSE_RECEIPT scenario=" + scenario + " port=" + port
			+ " connectionA=" + connection.connectionId() + " sessionA=" + a.session().getId()
			+ " sessionB=" + b.session().getId() + " closeA=" + connection.closeReason()
			+ " eventsA=" + first.eventNames() + " eventsB=" + second.eventNames()
			+ " upstreamCancelledA=" + firstPlan.cancellation.isCancelled()
			+ " upstreamCancelledB=" + secondPlan.cancellation.isCancelled()
			+ " sendAttemptsAfterReset=" + firstPlan.sendAttempts.get()
			+ " turnHttpA=" + firstStatus + " turnHttpB=" + secondStatus
			+ " persistedAiA=" + aiRows(a) + " persistedAiB=" + aiRows(b));
	}

	private enum Revocation { MEMBERSHIP_REMOVED, LINK_REMOVED }
	private record World(User instructor, Classroom classroom, ClassroomMember member,
		ClassroomWeekMaterial link, LearningSession session, String marker) { }

	private static final class ControlledTurn {
		private final String marker;
		private final boolean sendAfterReset;
		private final CountDownLatch next = new CountDownLatch(1);
		private final CountDownLatch finish = new CountDownLatch(1);
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicInteger sendAttempts = new AtomicInteger();
		private volatile AiStreamCancellation cancellation;

		private ControlledTurn(String marker, boolean sendAfterReset) {
			this.marker = marker;
			this.sendAfterReset = sendAfterReset;
		}

		private io.edupilot.ai.dto.TurnResponse execute(io.edupilot.ai.dto.TurnRequest request,
			Consumer<TurnStreamEvent> listener, AiStreamCancellation upstream) {
			calls.incrementAndGet();
			cancellation = upstream;
			listener.accept(TurnStreamEvent.contentDelta(marker + "-before"));
			await(next);
			if (sendAfterReset) {
				// Bound bytes ensure the OS reports a real closed socket even if one write was buffered.
				for (int attempt = 0; attempt < 64; attempt++) {
					sendAttempts.incrementAndGet();
					listener.accept(TurnStreamEvent.contentDelta("Synthetic-reset-probe-" + "x".repeat(65536)));
					if (upstream.isCancelled()) throw new AiClientException(ErrorCode.AI_STREAM_INTERRUPTED, false, null);
				}
				throw new IllegalStateException("Actual TCP reset was not observed within bounded sends");
			}
			listener.accept(TurnStreamEvent.contentDelta(marker + "-after"));
			await(finish);
			return new io.edupilot.ai.dto.TurnResponse("1.0", request.turnId(), "EXPLAIN", List.of(),
				List.of(Map.of("messageType", "EXPLANATION", "content", marker + "-complete")),
				Map.of("pageStatus", "EXPLAINED"), List.of(), null, List.of(), null, null);
		}

		private static void await(CountDownLatch barrier) {
			try {
				if (!barrier.await(25, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic HTTP barrier expired");
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Synthetic HTTP barrier interrupted", failure);
			}
		}
	}
}
