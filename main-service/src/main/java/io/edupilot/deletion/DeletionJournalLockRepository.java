package io.edupilot.deletion;

import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;

public interface DeletionJournalLockRepository extends JpaRepository<DeletionJournalLock, Integer> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select mutex from DeletionJournalLock mutex where mutex.id=1")
	Optional<DeletionJournalLock> lockSingleton();
}
