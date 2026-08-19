package io.edupilot.material;

import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import io.edupilot.ai.dto.OutlineResponse;

@Component
public class MaterialOutlineMarkdownRenderer {

	public String render(OutlineResponse response) {
		StringBuilder content = new StringBuilder(response.materialSummary())
			.append("\n\n## 목차\n\n");
		for (OutlineResponse.Section section : response.sections()) {
			content.append("- ")
				.append(section.title())
				.append(" (p.")
				.append(section.startPage())
				.append('–')
				.append(section.endPage())
				.append(')');
			if (section.keywords() != null && !section.keywords().isEmpty()) {
				StringJoiner keywords = new StringJoiner(", ");
				section.keywords().forEach(keywords::add);
				content.append(" — 키워드: ").append(keywords);
			}
			content.append('\n');
		}
		return content.toString().stripTrailing();
	}
}
