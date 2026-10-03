package io.edupilot.material;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.deletion.DeletionJournal;

@Service
public class MaterialXaiFileLifecycleService {

	private final DeletionJournal journal;
	public MaterialXaiFileLifecycleService(DeletionJournal journal) {
		this.journal = journal;
	}
	/** Persist with the caller transaction; a worker later applies an approved retention policy. */
	@Transactional
	public void deleteAfterCommit(String fileId) {
		journal.recordExternal(fileId);
	}
}
