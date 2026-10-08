package io.edupilot.material;

import java.io.InputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.deletion.DeletionJournal;
import io.edupilot.material.storage.FileStorage;

/** Serializes the short local write with logical deletion. Rendering and provider calls stay outside this transaction. */
@Service
public class MaterialRenderStorage {
	private final LearningMaterialRepository materials;
	private final DeletionJournal journal;
	private final FileStorage files;
	public MaterialRenderStorage(LearningMaterialRepository materials,DeletionJournal journal,FileStorage files) {
		this.materials=materials;this.journal=journal;this.files=files;
	}
	@Transactional
	public boolean store(String originalKey,String imageKey,InputStream input) {
		LearningMaterial material=materials.findByStorageKeyForUpdate(originalKey).orElse(null);
		if(material==null||!material.isActive()||journal.materialIsTombstoned(originalKey)) { return false; }
		if(!originalKey.endsWith(".pdf")||imageKey==null) {
			throw new IllegalArgumentException("Render key does not belong to material");
		}
		String expectedPrefix=originalKey.substring(0,originalKey.length()-4)+"-pages/";
		if(!imageKey.startsWith(expectedPrefix)) {
			throw new IllegalArgumentException("Render key does not belong to material");
		}
		files.storePageImage(input,imageKey); return true;
	}
}
