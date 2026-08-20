package io.edupilot.material;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.CaptionsRequest;
import io.edupilot.ai.dto.CaptionsResponse;
import io.edupilot.material.MaterialCaptionPersistenceService.CaptionSnapshot;
import io.edupilot.material.MaterialCaptionPersistenceService.PageSnapshot;
import io.edupilot.material.PageImageRenderer.RenderedPage;

@Service
public class MaterialCaptionGenerationService {

	private static final Logger log = LoggerFactory.getLogger(
		MaterialCaptionGenerationService.class
	);
	private static final int CHUNK_SIZE = 10;
	private static final String SCHEMA_VERSION = "1.0";

	private final MaterialCaptionPersistenceService persistenceService;
	private final PageImageRenderer imageRenderer;
	private final AiClient aiClient;
	private final Clock clock;

	public MaterialCaptionGenerationService(
		MaterialCaptionPersistenceService persistenceService,
		PageImageRenderer imageRenderer,
		AiClient aiClient,
		Clock clock
	) {
		this.persistenceService = persistenceService;
		this.imageRenderer = imageRenderer;
		this.aiClient = aiClient;
		this.clock = clock;
	}

	public void generate(Long materialId) {
		CaptionSnapshot snapshot = persistenceService.snapshot(materialId)
			.orElse(null);
		if (snapshot == null) {
			return;
		}
		Map<Integer, String> textByPage = new HashMap<>();
		List<Integer> pageNumbers = new ArrayList<>();
		for (PageSnapshot page : snapshot.pages()) {
			textByPage.put(page.pageNumber(), page.text());
			pageNumbers.add(page.pageNumber());
		}
		List<CaptionsRequest.Page> chunk = new ArrayList<>(CHUNK_SIZE);
		try {
			imageRenderer.render(
				snapshot.storageKey(),
				pageNumbers,
				rendered -> {
					chunk.add(toRequestPage(rendered, textByPage));
					if (chunk.size() == CHUNK_SIZE) {
						processChunk(materialId, chunk);
						chunk.clear();
					}
				}
			);
			if (!chunk.isEmpty()) {
				processChunk(materialId, chunk);
			}
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("materialId", materialId)
				.addKeyValue("reason", exception.getClass().getSimpleName())
				.log("Material caption rendering failed");
		} finally {
			persistenceService.markCompleted(materialId, clock.instant());
		}
	}

	private CaptionsRequest.Page toRequestPage(
		RenderedPage rendered,
		Map<Integer, String> textByPage
	) {
		return new CaptionsRequest.Page(
			rendered.pageNumber(),
			Base64.getEncoder().encodeToString(rendered.jpeg()),
			textByPage.get(rendered.pageNumber())
		);
	}

	private void processChunk(
		Long materialId,
		List<CaptionsRequest.Page> pages
	) {
		List<CaptionsRequest.Page> requestPages = List.copyOf(pages);
		try {
			CaptionsResponse response = aiClient.captions(
				new CaptionsRequest(SCHEMA_VERSION, requestPages)
			);
			Map<Integer, String> captions = new HashMap<>();
			for (CaptionsResponse.PageCaption caption : response.captions()) {
				if (caption.caption() != null) {
					captions.put(caption.pageNumber(), caption.caption());
				}
			}
			persistenceService.applyCaptions(materialId, captions);
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("materialId", materialId)
				.addKeyValue("firstPage", requestPages.getFirst().pageNumber())
				.addKeyValue("pageCount", requestPages.size())
				.addKeyValue("reason", exception.getClass().getSimpleName())
				.log("Material caption chunk failed");
		}
	}
}
