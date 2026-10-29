package io.edupilot.deletion;

import jakarta.persistence.*;

@Entity @Table(name = "deletion_journal_lock")
public class DeletionJournalLock {
	@Id private Integer id;
	protected DeletionJournalLock() {}
	public static DeletionJournalLock initial() { var lock = new DeletionJournalLock(); lock.id=1; return lock; }
}
