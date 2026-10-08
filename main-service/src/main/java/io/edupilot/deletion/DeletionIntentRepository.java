package io.edupilot.deletion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface DeletionIntentRepository extends JpaRepository<DeletionIntent, Long> {
	Optional<DeletionIntent> findByKeyHash(String hash);
	boolean existsByKeyHash(String hash);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select intent from DeletionIntent intent where intent.keyHash=:hash")
	Optional<DeletionIntent> findByKeyHashForUpdate(@Param("hash") String hash);
	interface Target {
		DeletionKind getKind();
		String getSourceMaterialKey();
		String getKeyHash();
	}
	@Query("select intent.kind as kind, intent.sourceMaterialKey as sourceMaterialKey, intent.keyHash as keyHash from DeletionIntent intent where intent.id=:id")
	Optional<Target> target(@Param("id") Long id);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select intent from DeletionIntent intent where intent.id=:id")
	Optional<DeletionIntent> findForUpdate(@Param("id") Long id);
	@Query("""
		select intent.id from DeletionIntent intent where intent.kind in :kinds
		and (intent.status in (io.edupilot.deletion.DeletionStatus.POLICY_PENDING,
			io.edupilot.deletion.DeletionStatus.READY,io.edupilot.deletion.DeletionStatus.RETRY,
			io.edupilot.deletion.DeletionStatus.REFERENCE_PENDING)
			or (intent.status=io.edupilot.deletion.DeletionStatus.LEASED and intent.leaseUntil<=:now))
		and intent.nextAttemptAt<=:now order by intent.id
		""")
	List<Long> candidates(@Param("now") Instant now, @Param("kinds") List<DeletionKind> kinds, Pageable page);
	List<DeletionIntent> findByIdGreaterThanOrderById(Long afterId, Pageable page);
}
