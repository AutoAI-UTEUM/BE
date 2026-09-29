package io.edupilot.material;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.OutlineRequest;
import io.edupilot.ai.dto.OutlineResponse;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialOutlinePersistenceService.OutlineSnapshot;

@Service
public class MaterialOutlineGenerationService {

	private static final Logger log = LoggerFactory.getLogger(
		MaterialOutlineGenerationService.class
	);
	private static final String SCHEMA_VERSION = "1.0";

	private final MaterialOutlinePersistenceService persistenceService;
	private final MaterialOutlineMarkdownRenderer renderer;
	private final AiClient aiClient;
	private final AiUsageService aiUsageService;
	private final PageQuizPlanProperties pageQuizPlanProperties;

	public MaterialOutlineGenerationService(
		MaterialOutlinePersistenceService persistenceService,
		MaterialOutlineMarkdownRenderer renderer,
		AiClient aiClient,
		AiUsageService aiUsageService,
		PageQuizPlanProperties pageQuizPlanProperties
	) {
		this.persistenceService = persistenceService;
		this.renderer = renderer;
		this.aiClient = aiClient;
		this.aiUsageService = aiUsageService;
		this.pageQuizPlanProperties = pageQuizPlanProperties;
	}

	public void generate(Long materialId) {
		try {
			Optional<OutlineSnapshot> snapshot = persistenceService.snapshot(
				materialId
			);
			if (snapshot.isEmpty()) {
				return;
			}
			boolean includePageQuizPlan = pageQuizPlanProperties.enabled()
				&& coversAllPages(snapshot.get());
			if (pageQuizPlanProperties.enabled() && !includePageQuizPlan) {
				log.atWarn().addKeyValue("materialId", materialId)
					.addKeyValue("reason", "INCOMPLETE_PAGES")
					.log("Skipped outline page quiz planning");
			}
			OutlineRequest request = new OutlineRequest(
				SCHEMA_VERSION,
				snapshot.get().xaiFileId(),
				snapshot.get().totalPages(),
				snapshot.get().pages(),
				includePageQuizPlan ? Boolean.TRUE : null
			);
			OutlineResponse response;
			try {
				response = aiClient.outline(request);
				aiUsageService.record(
					snapshot.get().ownerId(),
					AiFeature.OUTLINE,
					response == null ? null : response.usage(),
					true
				);
			} catch (AiClientException exception) {
				aiUsageService.record(
					snapshot.get().ownerId(),
					AiFeature.OUTLINE,
					null,
					false
				);
				throw exception;
			}
			if (!includePageQuizPlan && response != null) {
				response = response.withoutPageQuizPlan();
			}
			validate(response, request.totalPages());
			persistenceService.markReady(
				materialId,
				renderer.render(response),
				response
			);
		} catch (RuntimeException exception) {
			persistenceService.markFailed(materialId);
			log.atWarn()
				.addKeyValue("materialId", materialId)
				.addKeyValue("reason", safeReason(exception))
				.log("Material outline generation failed");
		}
	}

	private boolean coversAllPages(OutlineSnapshot snapshot) {
		if (snapshot.totalPages() < 1
			|| snapshot.pages().size() != snapshot.totalPages()) {
			return false;
		}
		for (int index = 0; index < snapshot.pages().size(); index++) {
			OutlineRequest.Page page = snapshot.pages().get(index);
			if (page == null || page.pageNumber() != index + 1) {
				return false;
			}
		}
		return true;
	}

	private void validate(OutlineResponse response, int expectedTotalPages) {
		if (response == null
			|| !SCHEMA_VERSION.equals(response.schemaVersion())
			|| response.totalPages() != expectedTotalPages
			|| !StringUtils.hasText(response.materialSummary())
			|| response.sections() == null
			|| response.sections().isEmpty()
			|| response.sections().size() > 10) {
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
		}
		int previousEndPage = 0;
		for (OutlineResponse.Section section : response.sections()) {
			if (!StringUtils.hasText(section.title())
				|| section.startPage() != previousEndPage + 1
				|| section.startPage() < 1
				|| section.endPage() < section.startPage()
				|| section.endPage() > expectedTotalPages
				|| section.keywords() == null) {
				throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
			}
			previousEndPage = section.endPage();
		}
		if (previousEndPage != expectedTotalPages) {
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
		}
	}

	private String safeReason(RuntimeException exception) {
		if (exception instanceof AiClientException aiException) {
			return aiException.errorCode().code();
		}
		return exception.getClass().getSimpleName();
	}
}
