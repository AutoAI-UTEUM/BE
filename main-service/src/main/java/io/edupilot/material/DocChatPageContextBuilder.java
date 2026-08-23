package io.edupilot.material;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.edupilot.ai.dto.DocChatRequest.ContextDocument;

@Component
public class DocChatPageContextBuilder {

	private final MaterialPageTextMerger pageTextMerger;

	public DocChatPageContextBuilder(MaterialPageTextMerger pageTextMerger) {
		this.pageTextMerger = pageTextMerger;
	}

	public List<ContextDocument> build(
		String materialTitle,
		List<MaterialPage> pages,
		int maxDocuments
	) {
		if (pages.isEmpty() || maxDocuments < 1) {
			return List.of();
		}
		int pagesPerDocument = (pages.size() + maxDocuments - 1) / maxDocuments;
		List<ContextDocument> documents = new ArrayList<>();
		for (int start = 0; start < pages.size(); start += pagesPerDocument) {
			List<MaterialPage> chunk = pages.subList(
				start,
				Math.min(start + pagesPerDocument, pages.size())
			);
			int firstPage = chunk.getFirst().getPageNumber();
			int lastPage = chunk.getLast().getPageNumber();
			String text = chunk.stream()
				.map(page -> "[p." + page.getPageNumber() + "]\n"
					+ pageTextMerger.mergeCaption(
						page.getTextContent(),
						page.getCaption()
					))
				.collect(java.util.stream.Collectors.joining("\n\n"));
			documents.add(new ContextDocument(
				materialTitle + " p." + firstPage + "-" + lastPage,
				text
			));
		}
		return List.copyOf(documents);
	}
}
