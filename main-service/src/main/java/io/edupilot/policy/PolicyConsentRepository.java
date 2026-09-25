package io.edupilot.policy;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.edupilot.user.UserStatus;

public interface PolicyConsentRepository extends JpaRepository<PolicyConsent, Long> {
	List<PolicyConsent> findByUser_IdOrderByAgreedAtDescIdDesc(Long userId);

	boolean existsByUser_IdAndPolicyTypeAndPolicyVersion(
		Long userId, PolicyType policyType, String policyVersion
	);

	long countByPolicyTypeAndPolicyVersionAndUser_Status(
		PolicyType policyType, String policyVersion, UserStatus status
	);
}
