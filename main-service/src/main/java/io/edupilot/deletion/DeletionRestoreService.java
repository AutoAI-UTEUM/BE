package io.edupilot.deletion;

import java.util.List;
import org.springframework.stereotype.Service;

/** Maintenance-only boundary. Keep traffic/workers stopped until replay has completed. */
@Service
public class DeletionRestoreService {
	private final DeletionJournal journal;
	private final DeletionReplay replay;
	public DeletionRestoreService(DeletionJournal journal,DeletionReplay replay) { this.journal=journal;this.replay=replay; }
	public List<DeletionReplay.Result> restoreBatch(List<DeletionSnapshot> trustedSnapshots,String restoreEpoch) {
		List<Long> ids=journal.importForRestore(trustedSnapshots,restoreEpoch);
		return ids.stream().map(replay::reapply).toList();
	}
}
