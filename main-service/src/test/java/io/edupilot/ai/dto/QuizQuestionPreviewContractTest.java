package io.edupilot.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class QuizQuestionPreviewContractTest {
	@Test
	void whitelistExactlyMatchesActualAiPublicModelsIncludingInheritedFields() throws Exception {
		Path models = Path.of("../ai-service/src/edupilot_ai/models");
		String previewSource = Files.readString(models.resolve("quiz_preview.py"));
		String quizSource = Files.readString(models.resolve("quiz.py"));
		assertThat(fields(QuizQuestionPreview.class))
			.isEqualTo(pythonFields(previewSource, "QuizQuestionStreamEvent"));
		Set<String> questionFields = pythonFields(quizSource, "QuestionBase");
		questionFields.addAll(pythonFields(previewSource, "PublicQuizQuestion"));
		assertThat(fields(QuizQuestionPreview.Question.class)).isEqualTo(questionFields);
		assertThat(fields(QuizQuestionPreview.Choice.class)).isEqualTo(pythonFields(quizSource, "QuizChoice"));
		assertThat(fields(QuizQuestionPreview.Coverage.class)).isEqualTo(pythonFields(quizSource, "QuizCoverage"));
	}

	private Set<String> fields(Class<?> type) {
		return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
			.collect(Collectors.toSet());
	}

	private Set<String> pythonFields(String source, String className) {
		var block = Pattern.compile("(?ms)^class " + className + "\\([^\\n]+\\):\\R(.*?)(?=^class |\\z)")
			.matcher(source);
		assertThat(block.find()).as("AI public model %s exists", className).isTrue();
		var fields = Pattern.compile("(?m)^    ([a-z][a-z0-9_]*):").matcher(block.group(1));
		Set<String> result = new LinkedHashSet<>();
		while (fields.find()) {
			String[] words = fields.group(1).split("_");
			StringBuilder camel = new StringBuilder(words[0]);
			for (int i = 1; i < words.length; i++) {
				camel.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
			}
			result.add(camel.toString());
		}
		return result;
	}
}
