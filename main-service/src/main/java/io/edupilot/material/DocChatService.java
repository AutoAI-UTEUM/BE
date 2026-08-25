package io.edupilot.material;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.DocChatRequest.ContextDocument;
import io.edupilot.material.dto.DocChatRequest;
import io.edupilot.material.dto.DocChatResponse;
import io.edupilot.quiz.QuizDocChatContextService;

@Service
public class DocChatService {

	private static final Logger log = LoggerFactory.getLogger(DocChatService.class);
	private static final String SCHEMA_VERSION = "1.0";
	private static final int MAX_AI_HISTORY = 10;

	private final AiClient aiClient;
	private final MaterialDocChatContextService materialContextService;
	private final QuizDocChatContextService quizContextService;

	public DocChatService(
		AiClient aiClient,
		MaterialDocChatContextService materialContextService,
		QuizDocChatContextService quizContextService
	) {
		this.aiClient = aiClient;
		this.materialContextService = materialContextService;
		this.quizContextService = quizContextService;
	}

	public DocChatResponse askMaterial(
		Long userId,
		Long materialId,
		DocChatRequest request
	) {
		return ask(
			materialId,
			"material",
			materialContextService.build(userId, materialId),
			request
		);
	}

	public DocChatResponse askQuiz(
		Long userId,
		Long materialId,
		DocChatRequest request
	) {
		return ask(
			materialId,
			"quiz",
			quizContextService.build(userId, materialId),
			request
		);
	}

	private DocChatResponse ask(
		Long materialId,
		String mode,
		List<ContextDocument> contextDocuments,
		DocChatRequest request
	) {
		List<io.edupilot.ai.dto.DocChatRequest.HistoryMessage> history = history(
			request.history()
		);
		io.edupilot.ai.dto.DocChatResponse response = aiClient.docChat(
			new io.edupilot.ai.dto.DocChatRequest(
				SCHEMA_VERSION,
				contextDocuments,
				history,
				request.question().trim()
			)
		);
		if (response.warnings().stream()
			.anyMatch(warning -> "CONTEXT_TRUNCATED".equals(warning.type()))) {
			log.info(
				"Doc chat context was truncated: materialId={}, mode={}, documents={}",
				materialId,
				mode,
				contextDocuments.size()
			);
		}
		return new DocChatResponse(
			response.answer(),
			response.warnings().stream()
				.map(warning -> new DocChatResponse.Warning(
					warning.type(),
					warning.message()
				))
				.toList()
		);
	}

	private List<io.edupilot.ai.dto.DocChatRequest.HistoryMessage> history(
		List<DocChatRequest.HistoryMessage> history
	) {
		if (history == null || history.isEmpty()) {
			return List.of();
		}
		int fromIndex = Math.max(0, history.size() - MAX_AI_HISTORY);
		return history.subList(fromIndex, history.size()).stream()
			.map(message -> new io.edupilot.ai.dto.DocChatRequest.HistoryMessage(
				message.role().name(),
				message.content().trim()
			))
			.toList();
	}
}
