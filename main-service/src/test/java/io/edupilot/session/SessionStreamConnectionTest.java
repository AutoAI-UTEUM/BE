package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.session.dto.MessageResponse;
import io.edupilot.session.dto.NoteDraft;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.session.dto.TurnStateResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SessionStreamConnectionTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void emitsExactExternalOrderAndPublicPayloads() throws Exception {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter
		);
		connection.begin(new AiStreamCancellation());
		connection.send(TurnStreamEvent.status("PLANNING"));
		connection.send(TurnStreamEvent.thoughtSummary("계획 중"));
		connection.send(TurnStreamEvent.contentDelta("답변"));
		connection.send(TurnStreamEvent.heartbeat());
		UiAction action = UiAction.quizProposal();
		connection.sendUiAction(action);
		TurnResponse response = response(action);
		connection.sendCompleted(response);

		assertThat(emitter.eventNames()).containsExactly(
			"status",
			"thought_summary",
			"content_delta",
			null,
			"ui_action",
			"completed"
		);
		assertThat(emitter.payload(0)).isEqualTo(Map.of("stage", "PLANNING"));
		assertThat(emitter.payload(1)).isEqualTo(Map.of("text", "계획 중"));
		assertThat(emitter.payload(2)).isEqualTo(Map.of("text", "답변"));
		assertThat(emitter.raw(3)).contains(":heartbeat");
		assertThat(emitter.payload(4)).isEqualTo(Map.of("action", action));
		assertThat(emitter.payload(5)).isEqualTo(Map.of("result", response));
		assertThat(emitter.rawEvents())
			.noneMatch(value -> value.contains("statePatch")
				|| value.contains("actionsExecuted")
				|| value.contains("memoryCandidates")
				|| value.startsWith("id:"));
	}

	@Test
	void diagnosisActionOmitsBinaryDecisionFields() throws Exception {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter
		);
		connection.begin(new AiStreamCancellation());
		connection.sendUiAction(
			UiAction.diagnosisQuestion("진단 질문", 30L)
		);

		JsonNode payload = objectMapper.valueToTree(emitter.payload(0));
		JsonNode action = payload.get("action");
		assertThat(action.get("type").textValue())
			.isEqualTo("DIAGNOSIS_QUESTION");
		assertThat(action.get("content").textValue()).isEqualTo("진단 질문");
		assertThat(action.get("diagnosisId").longValue()).isEqualTo(30L);
		assertThat(action.get("yesEvent")).isNull();
		assertThat(action.get("noEvent")).isNull();
	}

	@Test
	void noteDraftAppearsOnlyInCompletedEvent() {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter
		);
		connection.begin(new AiStreamCancellation());
		TurnResponse response = new TurnResponse(
			"turn-note",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(3, PageStatus.EXPLAINED, null),
			new NoteDraft("복습 노트", "## 핵심\n내용")
		);

		connection.sendCompleted(response);

		assertThat(emitter.eventNames()).containsExactly("completed");
		JsonNode payload = objectMapper.valueToTree(emitter.payload(0));
		assertThat(payload.get("result").get("noteDraft").get("title")
			.textValue()).isEqualTo("복습 노트");
		assertThat(emitter.eventNames()).doesNotContain("content_delta");
	}

	@Test
	void relayWriteFailureCancelsUpstream() {
		SseEmitter failingEmitter = new SseEmitter(0L) {
			@Override
			public synchronized void send(SseEventBuilder builder)
				throws IOException {
				throw new IOException("downstream closed");
			}
		};
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			failingEmitter
		);
		AiStreamCancellation cancellation = new AiStreamCancellation();
		connection.begin(cancellation);

		assertThatThrownBy(() ->
			connection.send(TurnStreamEvent.contentDelta("partial")))
			.isInstanceOfSatisfying(
				io.edupilot.ai.AiClientException.class,
				exception -> assertThat(exception.errorCode())
					.isEqualTo(
						io.edupilot.global.error.ErrorCode
							.AI_STREAM_INTERRUPTED
					)
			);
		assertThat(cancellation.isCancelled()).isTrue();
	}

	@Test
	void heartbeatIsCommentOnlyAfterTenSecondsOfInactivity() {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		AtomicLong clock = new AtomicLong();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter,
			clock::get
		);

		clock.set(java.time.Duration.ofSeconds(9).toNanos());
		connection.sendHeartbeatIfIdle(
			SessionStreamService.HEARTBEAT_INTERVAL.toNanos()
		);
		assertThat(emitter.rawEvents()).isEmpty();

		clock.set(java.time.Duration.ofSeconds(10).toNanos());
		connection.sendHeartbeatIfIdle(
			SessionStreamService.HEARTBEAT_INTERVAL.toNanos()
		);
		assertThat(emitter.rawEvents()).singleElement()
			.satisfies(value -> {
				assertThat(value).contains(":heartbeat");
				assertThat(value).doesNotContain("event:", "data:");
			});
	}

	@Test
	void errorIsTheOnlyTerminalEventAndCarriesPublicSchema() {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter
		);
		connection.begin(new AiStreamCancellation());
		connection.send(TurnStreamEvent.status("PLANNING"));
		connection.sendError(new SessionStreamError(
			"AI_SERVICE_TIMEOUT",
			"TIMEOUT",
			"AI 서비스 응답 시간이 초과되었습니다.",
			true,
			"trace-1"
		));

		assertThat(emitter.eventNames()).containsExactly("status", "error");
		JsonNode error = objectMapper.valueToTree(emitter.payload(1));
		assertThat(error.get("code").textValue())
			.isEqualTo("AI_SERVICE_TIMEOUT");
		assertThat(error.get("category").textValue()).isEqualTo("TIMEOUT");
		assertThat(error.get("retryable").booleanValue()).isTrue();
		assertThat(error.get("traceId").textValue()).isEqualTo("trace-1");
		assertThat(emitter.eventNames()).doesNotContain("completed");
	}

	private TurnResponse response(UiAction action) {
		return new TurnResponse(
			"turn-123",
			100L,
			List.of(new MessageResponse(
				501L,
				SenderType.AI,
				MessageType.EXPLANATION,
				"답변",
				3,
				ChatMessageStatus.COMPLETED,
				Instant.parse("2026-07-28T09:00:00Z")
			)),
			List.of(action),
			new TurnStateResponse(3, PageStatus.EXPLAINED, null)
		);
	}

	private static final class CapturingSseEmitter extends SseEmitter {

		private final List<List<Object>> events = new ArrayList<>();

		private CapturingSseEmitter() {
			super(0L);
		}

		@Override
		public synchronized void send(SseEventBuilder builder)
			throws IOException {
			events.add(builder.build().stream()
				.map(ResponseBodyEmitter.DataWithMediaType::getData)
				.toList());
		}

		List<String> eventNames() {
			return events.stream().map(this::eventName).toList();
		}

		Object payload(int index) {
			return events.get(index).stream()
				.filter(value -> !(value instanceof String))
				.findFirst()
				.orElse(null);
		}

		String raw(int index) {
			return events.get(index).stream()
				.map(String::valueOf)
				.reduce("", String::concat);
		}

		List<String> rawEvents() {
			return events.stream()
				.map(values -> values.stream()
					.map(String::valueOf)
					.reduce("", String::concat))
				.toList();
		}

		private String eventName(List<Object> values) {
			return values.stream()
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.filter(value -> value.startsWith("event:"))
				.map(value -> value.substring("event:".length()))
				.map(value -> value.lines().findFirst().orElse("").trim())
				.findFirst()
				.orElse(null);
		}
	}
}
