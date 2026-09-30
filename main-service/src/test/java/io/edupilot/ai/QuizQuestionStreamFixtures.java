package io.edupilot.ai;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.ai.dto.QuizQuestionPreview;
import io.edupilot.ai.dto.TurnResponse;
import tools.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockWebServer;

public final class QuizQuestionStreamFixtures {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private QuizQuestionStreamFixtures() {
	}

	public static QuizQuestionPreview preview(int index) {
		return preview(index, "MCQ");
	}

	public static QuizQuestionPreview preview(int index, String quizType) {
		return new QuizQuestionPreview(
			"quiz_question", "generation-1", quizType, "퀴즈",
			new QuizQuestionPreview.Coverage(2, 4), index, 5, true,
			new QuizQuestionPreview.Question(
				"q" + index, "문항 " + index, BigDecimal.TEN,
				"MCQ".equals(quizType) ? List.of(
					new QuizQuestionPreview.Choice("a", "A"),
					new QuizQuestionPreview.Choice("b", "B")
				) : null
			)
		);
	}

	public static String previewJson(int index) {
		return MAPPER.writeValueAsString(preview(index));
	}

	public static String completedJson(String turnId) {
		return completedJson(turnId, "MCQ");
	}

	public static String completedJson(String turnId, String quizType) {
		return MAPPER.writeValueAsString(Map.of(
			"type", "completed", "result", completed(turnId, quizType)
		));
	}

	public static TurnResponse completed(String turnId) {
		return completed(turnId, "MCQ");
	}

	private static TurnResponse completed(String turnId, String quizType) {
		return new TurnResponse(
			"1.0", turnId, "Generate a five-question checkpoint quiz",
			List.of(), List.of(), Map.of("pageStatus", "QUIZ_READY"), List.of(),
			new QuizGeneration(
				"1.0", "generation-1", quizType, new QuizGeneration.Coverage(2, 4),
				"퀴즈", 5, IntStream.rangeClosed(1, 5).mapToObj(index ->
					new QuizGeneration.Question(
						"q" + index, "문항 " + index, BigDecimal.TEN,
						"MCQ".equals(quizType) ? List.of(new QuizGeneration.Choice("a", "A"),
							new QuizGeneration.Choice("b", "B")) : null,
						"MCQ".equals(quizType) ? "a" : null,
						("MCQ".equals(quizType) || "OX".equals(quizType)) ? "비공개 해설" : null,
						"OX".equals(quizType) ? true : null,
						"SHORT".equals(quizType) ? "비공개 정답" : null,
						"SHORT".equals(quizType) ? List.of("비공개 기준") : null,
						"ESSAY".equals(quizType) ? "비공개 모범 답안" : null,
						"ESSAY".equals(quizType) ? List.of(
							new QuizGeneration.Rubric("비공개 루브릭", BigDecimal.ONE)) : null
					)
				).toList()
			), List.of(), null, null
		);
	}

	public static HttpAiClient client(MockWebServer server) {
		Duration timeout = Duration.ofSeconds(2);
		return new HttpAiClient(new AiClientProperties(
			server.url("/").uri(), "stream-test-token", timeout, timeout,
			timeout, timeout, timeout, timeout, timeout, timeout, timeout,
			timeout, timeout, timeout, timeout, timeout, timeout, timeout,
			timeout, timeout, timeout, "/health"
		));
	}
}
