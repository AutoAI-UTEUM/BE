package io.edupilot.material.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalVolumeStorageTest {

	@TempDir
	private Path tempDirectory;

	@Test
	void storesWithServerGeneratedKeyAndLoadsResource() throws Exception {
		LocalVolumeStorage storage = new LocalVolumeStorage(
			new StorageProperties(tempDirectory)
		);

		String storageKey = storage.store(new ByteArrayInputStream(
			"%PDF-test".getBytes(StandardCharsets.US_ASCII)
		));

		assertThat(storageKey)
			.matches("materials/[0-9a-f-]{36}\\.pdf")
			.doesNotContain("original");
		assertThat(storage.load(storageKey).getContentAsByteArray())
			.isEqualTo("%PDF-test".getBytes(StandardCharsets.US_ASCII));
	}

	@Test
	void rejectsTraversalAndMalformedKeys() {
		LocalVolumeStorage storage = new LocalVolumeStorage(
			new StorageProperties(tempDirectory)
		);

		assertThatThrownBy(() -> storage.load("../secret.pdf"))
			.isInstanceOf(StorageException.class);
		assertThatThrownBy(() -> storage.load("materials/not-a-uuid.pdf"))
			.isInstanceOf(StorageException.class);
	}
}
