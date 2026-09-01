package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.edupilot.ai.dto.OutlineResponse;

class MaterialOutlineMarkdownRendererTest {

	private final MaterialOutlineMarkdownRenderer renderer =
		new MaterialOutlineMarkdownRenderer();

	@Test
	void rendersSummarySectionsAndOptionalKeywordsDeterministically() {
		OutlineResponse response = new OutlineResponse(
			"1.0",
			"자료 전체 요약입니다.",
			List.of(
				new OutlineResponse.Section(
					"객체의 상태와 행동",
					1,
					2,
					List.of("객체", "상태")
				),
				new OutlineResponse.Section(
					"클래스와 객체",
					3,
					4,
					List.of()
				)
			),
			null,
			4,
			null
		);

		assertThat(renderer.render(response)).isEqualTo("""
			자료 전체 요약입니다.

			## 목차

			- 객체의 상태와 행동 (p.1–2) — 키워드: 객체, 상태
			- 클래스와 객체 (p.3–4)""");
	}
}
