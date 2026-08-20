package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.edupilot.material.storage.LocalVolumeStorage;
import io.edupilot.material.storage.StorageProperties;

class PageImageRendererTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void rendersTwoPdfPagesAsStoredJpegsWithStableKeys() throws Exception {
		LocalVolumeStorage storage = new LocalVolumeStorage(
			new StorageProperties(temporaryDirectory)
		);
		String materialKey = storage.store(new ByteArrayInputStream(twoPagePdf()));
		PageImageRenderer renderer = new PageImageRenderer(storage);
		List<PageImageRenderer.RenderedPage> rendered = new ArrayList<>();

		renderer.render(materialKey, List.of(1, 2), rendered::add);

		String uuid = materialKey.substring("materials/".length(), materialKey.length() - 4);
		assertThat(rendered).extracting(PageImageRenderer.RenderedPage::storageKey)
			.containsExactly(
				"materials/" + uuid + "-pages/1.jpg",
				"materials/" + uuid + "-pages/2.jpg"
			);
		assertThat(rendered).allSatisfy(page -> {
			assertThat(page.jpeg()).startsWith((byte) 0xff, (byte) 0xd8);
			assertThat(storage.load(page.storageKey()).exists()).isTrue();
		});
	}

	private byte[] twoPagePdf() throws Exception {
		try (PDDocument document = new PDDocument();
			ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.addPage(new PDPage());
			document.addPage(new PDPage());
			document.save(output);
			return output.toByteArray();
		}
	}
}
