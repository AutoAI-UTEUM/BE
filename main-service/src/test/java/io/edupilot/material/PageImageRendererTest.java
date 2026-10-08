package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.core.io.ByteArrayResource;

import io.edupilot.material.storage.FileStorage;
import io.edupilot.material.storage.LocalVolumeStorage;
import io.edupilot.material.storage.StorageProperties;
import io.edupilot.material.storage.StorageException;

class PageImageRendererTest {

	private PageImageRenderer renderer(FileStorage storage) {
		MaterialRenderStorage writer=mock(MaterialRenderStorage.class);
		when(writer.store(any(),any(),any())).thenAnswer(invocation->{
			storage.storePageImage(invocation.getArgument(2),invocation.getArgument(1)); return true;
		});
		return new PageImageRenderer(storage,writer);
	}
	@TempDir
	Path temporaryDirectory;

	@ParameterizedTest
	@CsvSource({"100000,600", "600,100000", "700,1000"})
	void limitsRequestedRasterBeforeCallingPdfRenderer(float width, float height) throws Exception {
		LocalVolumeStorage storage = new LocalVolumeStorage(new StorageProperties(temporaryDirectory));
		String materialKey;
		try (PDDocument document = new PDDocument();
			ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.addPage(new PDPage(new PDRectangle(width, height)));
			document.save(output);
			materialKey = storage.store(new ByteArrayInputStream(output.toByteArray()));
		}
		try (MockedConstruction<PDFRenderer> construction = mockConstruction(PDFRenderer.class,
			(mock, context) -> {
				doAnswer(invocation -> boundedRaster(width, height, invocation.<Float>getArgument(1) / 72f))
					.when(mock).renderImageWithDPI(anyInt(), anyFloat(), eq(ImageType.RGB));
				doAnswer(invocation -> boundedRaster(width, height, invocation.getArgument(1)))
					.when(mock).renderImage(anyInt(), anyFloat(), eq(ImageType.RGB));
			})) {
			List<PageImageRenderer.RenderedPage> rendered = new ArrayList<>();
			renderer(storage).render(materialKey, List.of(1), rendered::add);
			assertThat(construction.constructed()).hasSize(1);
			assertThat(rendered).hasSize(1);
		}
	}

	private BufferedImage boundedRaster(float width, float height, float scale) {
		long widthPixels = (long) Math.floor(width * scale);
		long heightPixels = (long) Math.floor(height * scale);
		assertThat(widthPixels).isBetween(1L, 1_600L);
		assertThat(heightPixels).isBetween(1L, 2_400L);
		assertThat(widthPixels * heightPixels).isLessThanOrEqualTo(2_560_000L);
		// Never allocate the untrusted dimensions, including when the old implementation fails.
		return new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
	}

	@ParameterizedTest
	@CsvSource({
		"100000,600,0", "600,100000,0", "700,1000,0",
		"600,100000,90", "100000,600,270", "768,768,0", "768.00006,1152,180"
	})
	void actualJpegMatchesBoundedRenderPlan(float width, float height, int rotation) throws Exception {
		PDPage page = new PDPage(new PDRectangle(width, height));
		page.setRotation(rotation);
		assertRenderedSize(page);
	}

	@Test
	void usesCropBoxClippedToMediaBox() throws Exception {
		PDPage page = new PDPage(new PDRectangle(300, 400));
		page.setCropBox(new PDRectangle(50, 40, 400, 500));
		page.setRotation(90);
		PageImageRenderer.RenderPlan plan = PageImageRenderer.renderPlan(page);
		assertThat(plan.width()).isEqualTo((int) Math.floor(360 * (150 / 72f)));
		assertThat(plan.height()).isEqualTo((int) Math.floor(250 * (150 / 72f)));
		assertRenderedSize(page);
	}

	@ParameterizedTest
	@CsvSource({"595.27563,841.8898", "612,792"})
	void portraitPaperRetains150DpiButLandscapeIsDownscaled(float width, float height) {
		PageImageRenderer.RenderPlan portrait = PageImageRenderer.renderPlan(
			new PDPage(new PDRectangle(width, height))
		);
		assertThat(portrait.scale()).isEqualTo(150 / 72f);
		PageImageRenderer.RenderPlan landscape = PageImageRenderer.renderPlan(
			new PDPage(new PDRectangle(height, width))
		);
		assertThat(landscape.scale()).isLessThan(150 / 72f);
		assertThat(landscape.width()).isLessThanOrEqualTo(1_600);
	}

	@Test
	void rejectsInvalidAndSubpixelDimensionsWithoutRendering() {
		for (float invalid : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
			PDPage invalidWidth = new PDPage();
			invalidWidth.setMediaBox(new PDRectangle(invalid, 600));
			assertThatThrownBy(() -> PageImageRenderer.renderPlan(invalidWidth))
				.as("invalid effective width %s", invalid).isInstanceOf(PageRenderingException.class)
				.hasMessage(CaptionFailureReason.INVALID_PAGE_DIMENSIONS.name());
			PDPage invalidHeight = new PDPage();
			invalidHeight.setMediaBox(new PDRectangle(600, invalid));
			assertThatThrownBy(() -> PageImageRenderer.renderPlan(invalidHeight))
				.as("invalid effective height %s", invalid).isInstanceOf(PageRenderingException.class)
				.hasMessage(CaptionFailureReason.INVALID_PAGE_DIMENSIONS.name());
		}
		PDPage outsideCrop = new PDPage(new PDRectangle(300, 400));
		outsideCrop.setCropBox(new PDRectangle(500, 500, 100, 100));
		assertThatThrownBy(() -> PageImageRenderer.renderPlan(outsideCrop))
			.isInstanceOf(PageRenderingException.class)
			.hasMessage(CaptionFailureReason.INVALID_PAGE_DIMENSIONS.name());
		assertThatThrownBy(() -> PageImageRenderer.renderPlan(
			new PDPage(new PDRectangle(Float.MAX_VALUE, Float.MIN_NORMAL))
		)).isInstanceOf(PageRenderingException.class)
			.hasMessage(CaptionFailureReason.RENDER_LIMIT_EXCEEDED.name());
		assertThatThrownBy(() -> PageImageRenderer.renderPlan(
			new PDPage(new PDRectangle(0.1f, 600))
		)).isInstanceOf(PageRenderingException.class)
			.hasMessage(CaptionFailureReason.RENDER_LIMIT_EXCEEDED.name());
	}

	@Test
	void hugeFiniteDimensionsDoNotOverflowPixelCalculation() {
		PageImageRenderer.RenderPlan plan = PageImageRenderer.renderPlan(
			new PDPage(new PDRectangle(Float.MAX_VALUE, Float.MAX_VALUE))
		);
		assertThat(plan.width()).isBetween(1, 1_600);
		assertThat(plan.height()).isBetween(1, 2_400);
		assertThat((long) plan.width() * plan.height()).isLessThanOrEqualTo(2_560_000);
		PDPage overflow = new PDPage(new PDRectangle(
			-Float.MAX_VALUE, 0, Float.MAX_VALUE, 600
		));
		// Finite coordinates can still have an infinite float difference.
		overflow.getMediaBox().setUpperRightX(Float.MAX_VALUE);
		assertThatThrownBy(() -> PageImageRenderer.renderPlan(overflow))
			.isInstanceOf(PageRenderingException.class);
	}

	@Test
	void invalidPageAndGeometryCloseDocumentWithoutCallingRenderer() throws Exception {
		for (int pageNumber : new int[]{0, 2, 1}) {
			try (PDDocument document = new PDDocument()) {
				document.addPage(new PDPage(new PDRectangle(0, 600)));
				FileStorage storage = mock(FileStorage.class);
				when(storage.load("materials/test.pdf")).thenReturn(new ByteArrayResource(new byte[]{1}));
				try (MockedStatic<Loader> loader = mockStatic(Loader.class);
					MockedConstruction<PDFRenderer> renderers = mockConstruction(PDFRenderer.class)) {
					loader.when(() -> Loader.loadPDF(any(byte[].class))).thenReturn(document);
					assertThatThrownBy(() -> renderer(storage).render(
						"materials/test.pdf", List.of(pageNumber), ignored -> { }
					)).isInstanceOf(StorageException.class);
					assertThat(document.getDocument().isClosed()).isTrue();
					verify(renderers.constructed().getFirst(), never())
						.renderImage(anyInt(), anyFloat(), any());
					verify(storage, never()).storePageImage(any(), any());
				}
			}
		}
	}

	@Test
	void renderingFailureClosesDocumentAndDoesNotStoreJpeg() throws Exception {
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			FileStorage storage = mock(FileStorage.class);
			when(storage.load("materials/test.pdf")).thenReturn(new ByteArrayResource(new byte[]{1}));
			try (MockedStatic<Loader> loader = mockStatic(Loader.class);
				MockedConstruction<PDFRenderer> renderers = mockConstruction(PDFRenderer.class,
					(renderer, context) -> when(renderer.renderImage(anyInt(), anyFloat(), any()))
						.thenThrow(new IOException("render failed")))) {
				loader.when(() -> Loader.loadPDF(any(byte[].class))).thenReturn(document);
				assertThatThrownBy(() -> renderer(storage).render(
					"materials/test.pdf", List.of(1), ignored -> { }
				)).isInstanceOf(StorageException.class).hasCauseInstanceOf(IOException.class);
				assertThat(document.getDocument().isClosed()).isTrue();
				verify(storage, never()).storePageImage(any(), any());
			}
		}
	}

	@Test
	void encodingFailureDisposesEncoderClosesOutputAndFlushesImage() throws Exception {
		ImageWriter writer = mock(ImageWriter.class);
		when(writer.getDefaultWriteParam()).thenReturn(new JPEGImageWriteParam(Locale.ROOT));
		doThrow(new IOException("encoding failed")).when(writer).write(any(), any(), any());
		List<ImageOutputStream> outputs = new ArrayList<>();
		BufferedImage image = spy(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			FileStorage storage = mock(FileStorage.class);
			when(storage.load("materials/test.pdf")).thenReturn(new ByteArrayResource(new byte[]{1}));
			try (MockedStatic<Loader> loader = mockStatic(Loader.class);
				MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class, CALLS_REAL_METHODS);
				MockedConstruction<PDFRenderer> renderers = mockConstruction(PDFRenderer.class,
					(renderer, context) -> when(renderer.renderImage(anyInt(), anyFloat(), any()))
						.thenReturn(image))) {
				loader.when(() -> Loader.loadPDF(any(byte[].class))).thenReturn(document);
				imageIo.when(() -> ImageIO.getImageWritersByFormatName("jpeg"))
					.thenReturn(Collections.singleton(writer).iterator());
				imageIo.when(() -> ImageIO.createImageOutputStream(any())).thenAnswer(invocation -> {
					ImageOutputStream output = spy(new MemoryCacheImageOutputStream(
						invocation.<OutputStream>getArgument(0)
					));
					outputs.add(output);
					return output;
				});
				assertThatThrownBy(() -> renderer(storage).render(
					"materials/test.pdf", List.of(1), ignored -> { }
				)).isInstanceOf(StorageException.class).hasCauseInstanceOf(IOException.class);
				verify(writer).dispose();
				verify(outputs.getFirst()).close();
				verify(image).flush();
				assertThat(document.getDocument().isClosed()).isTrue();
				verify(storage, never()).storePageImage(any(), any());
			}
		}
	}

	@Test
	void consumerFailureStillFlushesImageAndClosesDocument() throws Exception {
		BufferedImage image = spy(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			FileStorage storage = mock(FileStorage.class);
			when(storage.load("materials/test.pdf")).thenReturn(new ByteArrayResource(new byte[]{1}));
			try (MockedStatic<Loader> loader = mockStatic(Loader.class);
				MockedConstruction<PDFRenderer> renderers = mockConstruction(PDFRenderer.class,
					(renderer, context) -> when(renderer.renderImage(anyInt(), anyFloat(), any()))
						.thenReturn(image))) {
				loader.when(() -> Loader.loadPDF(any(byte[].class))).thenReturn(document);
				assertThatThrownBy(() -> renderer(storage).render(
					"materials/test.pdf", List.of(1), ignored -> { throw new IllegalStateException("consumer"); }
				)).isInstanceOf(IllegalStateException.class).hasMessage("consumer");
				verify(image).flush();
				assertThat(document.getDocument().isClosed()).isTrue();
			}
		}
	}

	@Test
	void rendersMixedDocumentsWithFourConcurrentTasks() throws Exception {
		LocalVolumeStorage storage = new LocalVolumeStorage(new StorageProperties(temporaryDirectory));
		List<String> keys = new ArrayList<>();
		for (int task = 0; task < 4; task++) {
			keys.add(storePdf(storage, new PDPage(), new PDPage(new PDRectangle(700, 1000))));
		}
		CountDownLatch start = new CountDownLatch(1);
		var memory = ManagementFactory.getMemoryMXBean();
		AtomicLong sampledPeak = new AtomicLong(memory.getHeapMemoryUsage().getUsed());
		try (var sampler = Executors.newSingleThreadScheduledExecutor();
			var executor = Executors.newFixedThreadPool(4)) {
			sampler.scheduleAtFixedRate(() -> sampledPeak.accumulateAndGet(
				memory.getHeapMemoryUsage().getUsed(), Math::max
			), 0, 10, TimeUnit.MILLISECONDS);
			List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
			for (int task = 0; task < 4; task++) {
				String key = keys.get(task);
				results.add(executor.submit(() -> {
					assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
					List<PageImageRenderer.RenderedPage> pages = new ArrayList<>();
					renderer(storage).render(key, List.of(1, 2), pages::add);
					for (var page : pages) {
						BufferedImage jpeg = ImageIO.read(new ByteArrayInputStream(page.jpeg()));
						try {
							assertThat((long) jpeg.getWidth() * jpeg.getHeight()).isLessThanOrEqualTo(2_560_000);
						} finally {
							jpeg.flush();
						}
					}
					return pages.size();
				}));
			}
			start.countDown();
			for (var result : results) {
				assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(2);
			}
		}
		// A 10ms heap sample is neither production RSS nor a guaranteed instantaneous peak.
		System.out.printf("PDF render probe: tasks=4, maxHeapBytes=%d, sampledHeapPeakBytes=%d%n",
			Runtime.getRuntime().maxMemory(), sampledPeak.get());
	}

	private void assertRenderedSize(PDPage page) throws Exception {
		PageImageRenderer.RenderPlan plan = PageImageRenderer.renderPlan(page);
		LocalVolumeStorage storage = new LocalVolumeStorage(new StorageProperties(temporaryDirectory));
		String key = storePdf(storage, page);
		List<PageImageRenderer.RenderedPage> pages = new ArrayList<>();
		renderer(storage).render(key, List.of(1), pages::add);
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(pages.getFirst().jpeg()));
		try {
			assertThat(image.getWidth()).isEqualTo(plan.width()).isBetween(1, 1_600);
			assertThat(image.getHeight()).isEqualTo(plan.height()).isBetween(1, 2_400);
			assertThat((long) image.getWidth() * image.getHeight()).isLessThanOrEqualTo(2_560_000);
		} finally {
			image.flush();
		}
	}

	private String storePdf(LocalVolumeStorage storage, PDPage... pages) throws Exception {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			for (PDPage page : pages) {
				document.addPage(page);
			}
			document.save(output);
			return storage.store(new ByteArrayInputStream(output.toByteArray()));
		}
	}

	@Test
	void rendersTwoPdfPagesAsStoredJpegsWithStableKeys() throws Exception {
		LocalVolumeStorage storage = new LocalVolumeStorage(
			new StorageProperties(temporaryDirectory)
		);
		String materialKey = storage.store(new ByteArrayInputStream(twoPagePdf()));
		PageImageRenderer renderer = renderer(storage);
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
