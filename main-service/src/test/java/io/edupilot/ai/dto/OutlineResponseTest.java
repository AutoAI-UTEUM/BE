package io.edupilot.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;

class OutlineResponseTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private Logger logger;
	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void attachAppender() {
		logger = (Logger) LoggerFactory.getLogger(OutlineResponse.class);
		appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@Test
	void deserializesDescriptionAndQuizCheckpoints() throws Exception {
		OutlineResponse response = objectMapper.readValue(
			outlineJson("""
				[
				  {"triggerPage":2,"coverage":{"startPage":1,"endPage":2}},
				  {"triggerPage":3,"coverage":{"startPage":3,"endPage":3}}
				]
				"""),
			OutlineResponse.class
		);

		assertThat(response.sections().getFirst().description())
			.isEqualTo("첫 단원의 설명");
		assertThat(response.quizCheckpoints())
			.containsExactly(
				new OutlineResponse.QuizCheckpoint(
					2,
					new OutlineResponse.Coverage(1, 2)
				),
				new OutlineResponse.QuizCheckpoint(
					3,
					new OutlineResponse.Coverage(3, 3)
				)
			);
		assertThat(appender.list).isEmpty();
	}

	@Test
	void deserializesLegacyJsonWithoutAdditiveFields() throws Exception {
		OutlineResponse response = objectMapper.readValue("""
			{
			  "schemaVersion":"1.0",
			  "materialSummary":"요약",
			  "sections":[{
			    "title":"기존 단원",
			    "startPage":1,
			    "endPage":3,
			    "keywords":[]
			  }],
			  "totalPages":3
			}
			""", OutlineResponse.class);

		assertThat(response.sections().getFirst().description()).isNull();
		assertThat(response.quizCheckpoints()).isNull();
		assertThat(appender.list).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("invalidCheckpoints")
	void invalidCheckpointsAreIgnoredAndWarnedWithoutRawContent(
		String checkpoints,
		String violationType
	) throws Exception {
		OutlineResponse response = objectMapper.readValue(
			outlineJson(checkpoints),
			OutlineResponse.class
		);

		assertThat(response.quizCheckpoints()).isNull();
		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage())
				.isEqualTo("Ignored invalid outline quiz checkpoints")
				.doesNotContain("첫 단원의 설명", checkpoints);
			assertThat(event.getKeyValuePairs())
				.extracting(pair -> pair.key + "=" + pair.value)
				.containsExactly("violationType=" + violationType);
		});
	}

	private static Stream<Arguments> invalidCheckpoints() {
		return Stream.of(
			Arguments.of("[]", "COUNT_OUT_OF_RANGE"),
			Arguments.of(tooManyCheckpoints(), "COUNT_OUT_OF_RANGE"),
			Arguments.of("""
				[{"triggerPage":2,"coverage":{"startPage":1,"endPage":3}}]
				""", "TRIGGER_MISMATCH"),
			Arguments.of("""
				[{"triggerPage":2,"coverage":{"startPage":3,"endPage":2}}]
				""", "RANGE_REVERSED"),
			Arguments.of("""
				[{"triggerPage":4,"coverage":{"startPage":1,"endPage":4}}]
				""", "RANGE_OUT_OF_BOUNDS"),
			Arguments.of("""
				[
				  {"triggerPage":2,"coverage":{"startPage":1,"endPage":2}},
				  {"triggerPage":2,"coverage":{"startPage":1,"endPage":2}}
				]
				""", "DUPLICATE_TRIGGER"),
			Arguments.of("""
				[
				  {"triggerPage":3,"coverage":{"startPage":3,"endPage":3}},
				  {"triggerPage":2,"coverage":{"startPage":1,"endPage":2}}
				]
				""", "TRIGGER_ORDER_INVALID"),
			Arguments.of("""
				[
				  {"triggerPage":2,"coverage":{"startPage":1,"endPage":2}},
				  {"triggerPage":3,"coverage":{"startPage":1,"endPage":3}}
				]
				""", "COVERAGE_OVERLAP"),
			Arguments.of("""
				[{"triggerPage":3,"coverage":{"startPage":2,"endPage":3}}]
				""", "SECTION_BOUNDARY_MISMATCH")
		);
	}

	private static String tooManyCheckpoints() {
		return "[" + String.join(
			",",
			java.util.Collections.nCopies(
				11,
				"{\"triggerPage\":2,\"coverage\":"
					+ "{\"startPage\":1,\"endPage\":2}}"
			)
		) + "]";
	}

	private String outlineJson(String checkpoints) {
		return """
			{
			  "schemaVersion":"1.0",
			  "materialSummary":"요약",
			  "sections":[
			    {
			      "title":"첫 단원",
			      "description":"첫 단원의 설명",
			      "startPage":1,
			      "endPage":2,
			      "keywords":["첫째"]
			    },
			    {
			      "title":"둘째 단원",
			      "description":"둘째 단원의 설명",
			      "startPage":3,
			      "endPage":3,
			      "keywords":["둘째"]
			    }
			  ],
			  "quizCheckpoints":%s,
			  "totalPages":3
			}
			""".formatted(checkpoints);
	}
}
