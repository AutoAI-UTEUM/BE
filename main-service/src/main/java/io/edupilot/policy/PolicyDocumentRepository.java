package io.edupilot.policy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {
	Optional<PolicyDocument> findFirstByTypeAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(
		PolicyType type, Instant now
	);

	Optional<PolicyDocument> findByTypeAndVersion(PolicyType type, String version);

	boolean existsByTypeAndVersion(PolicyType type, String version);

	List<PolicyDocument> findAllByOrderByEffectiveAtDescIdDesc();

	List<PolicyDocument> findByTypeOrderByEffectiveAtDescIdDesc(PolicyType type);
}
