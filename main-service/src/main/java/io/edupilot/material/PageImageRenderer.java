package io.edupilot.material;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import io.edupilot.material.storage.FileStorage;
import io.edupilot.material.storage.StorageException;

@Component
public class PageImageRenderer {

	private static final float DPI = 150;
	private static final int MAX_WIDTH = 1_600;
	private static final float JPEG_QUALITY = 0.8f;
	private static final String MATERIAL_PREFIX = "materials/";
	private static final String PDF_SUFFIX = ".pdf";

	private final FileStorage fileStorage;

	public PageImageRenderer(FileStorage fileStorage) {
		this.fileStorage = fileStorage;
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
				BufferedImage rendered = renderer.renderImageWithDPI(
					pageNumber - 1,
					DPI,
					ImageType.RGB
				);
				BufferedImage scaled = scaleDown(rendered);
				byte[] jpeg = encodeJpeg(scaled);
				if (scaled != rendered) {
					rendered.flush();
				}
				scaled.flush();
				String imageKey = imageKey(storageKey, pageNumber);
				fileStorage.storePageImage(
					new ByteArrayInputStream(jpeg),
					imageKey
				);
				consumer.accept(new RenderedPage(pageNumber, imageKey, jpeg));
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

	private BufferedImage scaleDown(BufferedImage source) {
		if (source.getWidth() <= MAX_WIDTH) {
			return source;
		}
		int height = Math.max(
			1,
			(int) Math.round(source.getHeight() * (MAX_WIDTH / (double) source.getWidth()))
		);
		BufferedImage target = new BufferedImage(
			MAX_WIDTH,
			height,
			BufferedImage.TYPE_INT_RGB
		);
		Graphics2D graphics = target.createGraphics();
		try {
			graphics.setRenderingHint(
				RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR
			);
			graphics.drawImage(source, 0, 0, MAX_WIDTH, height, null);
		} finally {
			graphics.dispose();
		}
		return target;
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
}
