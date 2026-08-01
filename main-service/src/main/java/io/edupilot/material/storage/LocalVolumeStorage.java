package io.edupilot.material.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class LocalVolumeStorage implements FileStorage {

	private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile(
		"(?:materials/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
			+ "[0-9a-f]{4}-[0-9a-f]{12}\\.pdf|"
			+ "avatars/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
			+ "[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png|webp))"
	);
	private static final Set<String> AVATAR_EXTENSIONS = Set.of("jpg", "png", "webp");

	private final Path rootDirectory;

	public LocalVolumeStorage(StorageProperties properties) {
		this.rootDirectory = properties.rootDirectory()
			.toAbsolutePath()
			.normalize();
	}

	@Override
	public String store(InputStream inputStream) {
		return store(inputStream, "materials/" + UUID.randomUUID() + ".pdf");
	}

	@Override
	public String storeAvatar(InputStream inputStream, String extension) {
		if (!AVATAR_EXTENSIONS.contains(extension)) {
			throw new StorageException("지원하지 않는 아바타 확장자입니다.");
		}
		return store(inputStream, "avatars/" + UUID.randomUUID() + "." + extension);
	}

	private String store(InputStream inputStream, String storageKey) {
		Path target = resolve(storageKey);
		try {
			Files.createDirectories(target.getParent());
			Files.copy(inputStream, target);
			return storageKey;
		} catch (IOException exception) {
			try {
				Files.deleteIfExists(target);
			} catch (IOException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			throw new StorageException("파일 저장에 실패했습니다.", exception);
		}
	}

	@Override
	public void delete(String storageKey) {
		Path target = resolve(storageKey);
		try {
			Files.deleteIfExists(target);
		} catch (IOException exception) {
			throw new StorageException("저장된 파일 삭제에 실패했습니다.", exception);
		}
	}

	@Override
	public Resource load(String storageKey) {
		Path target = resolve(storageKey);
		if (!Files.isRegularFile(target)) {
			throw new StorageException("저장된 파일을 찾을 수 없습니다.");
		}
		return new FileSystemResource(target);
	}

	private Path resolve(String storageKey) {
		if (storageKey == null || !STORAGE_KEY_PATTERN.matcher(storageKey).matches()) {
			throw new StorageException("유효하지 않은 저장소 키입니다.");
		}
		Path resolved = rootDirectory.resolve(storageKey).normalize();
		if (!resolved.startsWith(rootDirectory)) {
			throw new StorageException("저장소 경로를 벗어날 수 없습니다.");
		}
		return resolved;
	}
}
