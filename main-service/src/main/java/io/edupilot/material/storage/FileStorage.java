package io.edupilot.material.storage;

import java.io.InputStream;

import org.springframework.core.io.Resource;

public interface FileStorage {

	String store(InputStream inputStream);

	Resource load(String storageKey);
}
