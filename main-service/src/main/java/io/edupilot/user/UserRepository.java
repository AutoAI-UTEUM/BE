package io.edupilot.user;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);

	long countByStatus(UserStatus status);

	@Transactional(readOnly = true)
	@Query("select new io.edupilot.user.UserAccessState(account.role, account.status) from User account where account.id = :userId")
	Optional<UserAccessState> findAccessStateById(@Param("userId") Long userId);

	@Transactional(readOnly = true)
	@Query("""
		select new io.edupilot.user.UserBusinessAccessState(
			account.role, account.status, account.emailVerificationState, account.emailVerifiedAt,
			account.accessCohort, account.ageVerificationState, account.dateOfBirth,
			account.guardianApprovedUntil, account.guardianAiConsentAllowed, account.guardianConsentEpoch,
			account.guardianApprovalPolicyDigest)
		from User account where account.id = :userId
		""")
	Optional<UserBusinessAccessState> findBusinessAccessStateById(@Param("userId") Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from User account where account.email = :email")
	Optional<User> findByEmailForUpdate(@Param("email") String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from User account where account.id = :userId")
	Optional<User> findByIdForUpdate(@Param("userId") Long userId);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select account from User account where account.id = :userId")
	Optional<User> findByIdForBusinessAccess(@Param("userId") Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select account from User account
		where account.role = io.edupilot.user.UserRole.ADMIN
		  and account.status = io.edupilot.user.UserStatus.ACTIVE
		order by account.id
		""")
	List<User> findActiveAdminsForUpdate();

	Optional<User> findByGoogleSub(String googleSub);

	@Modifying
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	@Query("""
		update User account
		set account.lastActiveAt = :lastActiveAt
		where account.id = :userId
		  and account.status = io.edupilot.user.UserStatus.ACTIVE
		""")
	int updateLastActiveAt(
		@Param("userId") Long userId,
		@Param("lastActiveAt") Instant lastActiveAt
	);

	@Query("""
		select account
		from User account
		where (:query is null
		       or lower(account.email) like lower(concat('%', :query, '%'))
		       or lower(account.name) like lower(concat('%', :query, '%')))
		  and (:role is null or account.role = :role)
		  and (:status is null or account.status = :status)
		""")
	Page<User> findAdminUsers(
		@Param("query") String query,
		@Param("role") UserRole role,
		@Param("status") UserStatus status,
		Pageable pageable
	);
}
