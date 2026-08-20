package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaterialPageTextMergerTest {

	private final MaterialPageTextMerger merger = new MaterialPageTextMerger();

	@Test
	void appendsOnlyMeaningfulCaptionWithStableMarker() {
		assertThat(merger.mergeCaption("본문", "도표 설명"))
			.isEqualTo("본문\n\n[그림 설명] 도표 설명");
		assertThat(merger.mergeCaption("본문", null)).isEqualTo("본문");
		assertThat(merger.mergeCaption("본문", "   ")).isEqualTo("본문");
	}
}
