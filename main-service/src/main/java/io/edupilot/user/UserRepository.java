package io.edupilot.user;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);

	Optional<User> findByGoogleSub(String googleSub);

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
