package io.edupilot.material;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import io.edupilot.material.storage.FileStorage;
import io.edupilot.material.storage.StorageException;

@Component
public class PageImageRenderer {

	private static final float DPI = 150;
	private static final int MAX_WIDTH = 1_600;
	private static final int MAX_HEIGHT = 2_400;
	private static final long MAX_PIXELS = 2_560_000;
	private static final float JPEG_QUALITY = 0.8f;
	private static final String MATERIAL_PREFIX = "materials/";
	private static final String PDF_SUFFIX = ".pdf";

	private final FileStorage fileStorage;
	private final MaterialRenderStorage renderStorage;

	public PageImageRenderer(FileStorage fileStorage,MaterialRenderStorage renderStorage) {
		this.fileStorage = fileStorage;
		this.renderStorage = renderStorage;
	}

	public void render(
		String storageKey,
		List<Integer> pageNumbers,
		Consumer<RenderedPage> consumer
	) {
		try (PDDocument document = Loader.loadPDF(
			fileStorage.load(storageKey).getContentAsByteArray()
		)) {
			PDFRenderer renderer = new PDFRenderer(document);
			for (int pageNumber : pageNumbers) {
				if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
					throw new StorageException("PDF page number is out of range");
				}
				RenderPlan plan = renderPlan(document.getPage(pageNumber - 1));
				BufferedImage rendered = renderer.renderImage(
					pageNumber - 1,
					plan.scale(),
					ImageType.RGB
				);
				try {
					byte[] jpeg = encodeJpeg(rendered);
					String imageKey = imageKey(storageKey, pageNumber);
					if (!renderStorage.store(storageKey,imageKey,new ByteArrayInputStream(jpeg))) { return; }
					consumer.accept(new RenderedPage(pageNumber, imageKey, jpeg));
				} finally {
					rendered.flush();
				}
			}
		} catch (IOException exception) {
			throw new StorageException("Failed to render material pages", exception);
		}
	}

	String imageKey(String storageKey, int pageNumber) {
		if (storageKey == null
			|| !storageKey.startsWith(MATERIAL_PREFIX)
			|| !storageKey.endsWith(PDF_SUFFIX)) {
			throw new StorageException("Invalid material storage key");
		}
		String uuid = storageKey.substring(
			MATERIAL_PREFIX.length(),
			storageKey.length() - PDF_SUFFIX.length()
		);
		return MATERIAL_PREFIX + uuid + "-pages/" + pageNumber + ".jpg";
	}

	static RenderPlan renderPlan(PDPage page) {
		PDRectangle box = page.getCropBox(); // PDFBox clips CropBox to MediaBox.
		float width = box.getWidth();
		float height = box.getHeight();
		if (!Float.isFinite(box.getLowerLeftX()) || !Float.isFinite(box.getLowerLeftY())
			|| !Float.isFinite(box.getUpperRightX()) || !Float.isFinite(box.getUpperRightY())
			|| !Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) {
			throw new PageRenderingException(CaptionFailureReason.INVALID_PAGE_DIMENSIONS);
		}
		boolean rotated = page.getRotation() == 90 || page.getRotation() == 270;
		double outputWidth = rotated ? height : width;
		double outputHeight = rotated ? width : height;
		double scaleLimit = Math.min(DPI / 72f, Math.min(
			Math.min(MAX_WIDTH / outputWidth, MAX_HEIGHT / outputHeight),
			Math.sqrt(MAX_PIXELS / ((double) width * height))
		));
		float scale = (float) scaleLimit;
		if (scale > scaleLimit) {
			scale = Math.nextDown(scale);
		}
		while (Float.isFinite(scale) && scale > 0) {
			// Match PDFBox's float multiplication followed by floor, before its 1px clamp.
			double widthPixels = Math.floor(width * scale);
			double heightPixels = Math.floor(height * scale);
			if (widthPixels < 1 || heightPixels < 1) {
				break;
			}
			double rotatedWidth = rotated ? heightPixels : widthPixels;
			double rotatedHeight = rotated ? widthPixels : heightPixels;
			if (rotatedWidth <= MAX_WIDTH && rotatedHeight <= MAX_HEIGHT
				&& (long) rotatedWidth * (long) rotatedHeight <= MAX_PIXELS) {
				return new RenderPlan(scale, (int) rotatedWidth, (int) rotatedHeight);
			}
			scale = Math.nextDown(scale);
		}
		throw new PageRenderingException(CaptionFailureReason.RENDER_LIMIT_EXCEEDED);
	}

	private byte[] encodeJpeg(BufferedImage image) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try (ByteArrayOutputStream output = new ByteArrayOutputStream();
			ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
			writer.setOutput(imageOutput);
			ImageWriteParam parameters = writer.getDefaultWriteParam();
			parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			parameters.setCompressionQuality(JPEG_QUALITY);
			writer.write(null, new IIOImage(image, null, null), parameters);
			return output.toByteArray();
		} finally {
			writer.dispose();
		}
	}

	public record RenderedPage(
		int pageNumber,
		String storageKey,
		byte[] jpeg
	) {
	}

	record RenderPlan(float scale, int width, int height) {
	}
}
