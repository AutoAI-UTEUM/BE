package io.edupilot.material.storage;

import java.io.InputStream;

import org.springframework.core.io.Resource;

public interface FileStorage {

	String store(InputStream inputStream);

	String storeAvatar(InputStream inputStream, String extension);

	Resource load(String storageKey);

	void delete(String storageKey);
}
